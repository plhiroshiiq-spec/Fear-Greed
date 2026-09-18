"""data/fg.json (schema 2) と data/history_us.csv を作る (SPEC.md 2章 / 4章)。

設計原則(0章)のうち、この層が守るもの:
- 壊れても嘘をつかない: 取得失敗時は前回値を保持し ``stale`` を立てる。推測値で埋めない。
- CNNの値をそのまま出す: 総合スコアは再計算しない。
- CNNの生データは保存しない: スコアと派生統計だけを書き出す。
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import logging
import sys
from pathlib import Path
from typing import Any

from . import cnn
from .cnn import CnnError, CnnFetchError, CnnValidationError

log = logging.getLogger("fg.build_json")

SCHEMA = 2
HISTORY_LEN = 260
JST = dt.timezone(dt.timedelta(hours=9))

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATA_DIR = REPO_ROOT / "data"

FG_JSON = "fg.json"
HISTORY_CSV = "history_us.csv"
FAILURE_STATE = "failure_state.json"

#: 4章 手順4: 3回連続で失敗したらワークフローを失敗させる。
MAX_CONSECUTIVE_FAILURES = 3

CSV_HEADER = ["date", "score"] + [c.key for c in cnn.COMPONENTS]


# --------------------------------------------------------------------------
# 派生統計
# --------------------------------------------------------------------------

def compute_streak(history: list[tuple[dt.date, float]]) -> int:
    """同方向の連続日数。上昇は正、下降は負。横ばい(差0)で途切れる。"""
    if len(history) < 2:
        return 0
    diffs = [round(history[i][1] - history[i - 1][1], 10) for i in range(1, len(history))]
    last = diffs[-1]
    if last == 0:
        return 0
    sign = 1 if last > 0 else -1
    count = 0
    for d in reversed(diffs):
        if (d > 0 and sign == 1) or (d < 0 and sign == -1):
            count += 1
        else:
            break
    return sign * count


def _f2(value: Any) -> float | None:
    if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return round(float(value), 2)


# --------------------------------------------------------------------------
# 組み立て
# --------------------------------------------------------------------------

def build_document(payload: dict, now: dt.datetime | None = None) -> dict:
    """検証済みのCNNペイロードから fg.json の中身を作る。"""
    now = now or dt.datetime.now(JST)
    fg = payload["fear_and_greed"]

    history = cnn.historical_series(payload)
    as_of = cnn.to_utc_date(fg["timestamp"]) if fg.get("timestamp") is not None else history[-1][0]

    score = round(float(fg["score"]), 2)
    prev_close = _f2(fg.get("previous_close"))
    delta = None if prev_close is None else round(score - prev_close, 2)

    return {
        "schema": SCHEMA,
        "generated_at": now.isoformat(timespec="seconds"),
        "us": {
            "source": "cnn",
            "as_of": as_of.isoformat(),
            "score": score,
            "rating": str(fg["rating"]).strip().lower(),
            "prev_close": prev_close,
            "w1": _f2(fg.get("previous_1_week")),
            "m1": _f2(fg.get("previous_1_month")),
            "y1": _f2(fg.get("previous_1_year")),
            "delta": delta,
            "streak": compute_streak(history),
            "components": cnn.component_scores(payload),
            # 直近260営業日。日付でdedupe済み。足りない分は埋めない。
            "history": [[d.isoformat(), round(v, 2)] for d, v in history[-HISTORY_LEN:]],
            "stale": False,
        },
        # "jp" は予約のみ。v2.0では出力しない(SPEC.md 2章)。
    }


# --------------------------------------------------------------------------
# 入出力
# --------------------------------------------------------------------------

def read_json(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        with path.open(encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as exc:
        log.warning("could not read %s: %s", path, exc)
        return None


def write_json(path: Path, doc: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    tmp.replace(path)


def previous_as_of(doc: dict | None) -> dt.date | None:
    if not doc:
        return None
    value = (doc.get("us") or {}).get("as_of")
    if not isinstance(value, str):
        return None
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        return None


def upsert_history_csv(path: Path, doc: dict) -> None:
    """date, score, 各要素score を追記保存する。同じ日付は上書きする。"""
    us = doc["us"]
    rows: dict[str, list[str]] = {}
    if path.exists():
        with path.open(newline="", encoding="utf-8") as fh:
            reader = csv.reader(fh)
            header = next(reader, None)
            if header == CSV_HEADER:
                for row in reader:
                    if row:
                        rows[row[0]] = row
            else:
                log.warning("%s header changed; rebuilding from this run only", path)

    by_key = {c["key"]: c["score"] for c in us["components"]}
    rows[us["as_of"]] = [us["as_of"], f"{us['score']:.2f}"] + [
        "" if by_key.get(c.key) is None else f"{by_key[c.key]:.1f}" for c in cnn.COMPONENTS
    ]

    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(CSV_HEADER)
        for key in sorted(rows):
            writer.writerow(rows[key])
    tmp.replace(path)


# --------------------------------------------------------------------------
# 失敗時の挙動 (4章 手順3〜4)
# --------------------------------------------------------------------------

def _load_failure_count(path: Path) -> int:
    doc = read_json(path) or {}
    value = doc.get("consecutive_failures")
    return value if isinstance(value, int) and value >= 0 else 0


def _save_failure_state(path: Path, count: int, reason: str | None, now: dt.datetime) -> None:
    write_json(
        path,
        {
            "consecutive_failures": count,
            "last_failure_at": None if count == 0 else now.isoformat(timespec="seconds"),
            "last_failure_reason": None if count == 0 else reason,
        },
    )


def mark_stale(data_dir: Path, reason: str, now: dt.datetime) -> int:
    """既存の fg.json を上書きせず ``stale:true`` だけを立てる。

    ``generated_at`` は **更新しない**。SUMI DECK 側の「36時間以上前なら更新遅延」
    判定を殺さないため(SPEC.md 5章)。
    戻り値は終了コード。3回連続で失敗したときだけ 1(ワークフローを失敗させる)。
    """
    fg_path = data_dir / FG_JSON
    state_path = data_dir / FAILURE_STATE

    count = _load_failure_count(state_path) + 1
    _save_failure_state(state_path, count, reason, now)

    doc = read_json(fg_path)
    if doc is None:
        # 前回値が無い。推測値で埋めるくらいなら何も書かない(設計原則1)。
        log.error("fetch failed and no previous fg.json exists; nothing written: %s", reason)
    else:
        us = doc.setdefault("us", {})
        us["stale"] = True
        write_json(fg_path, doc)
        log.error("fetch failed; kept previous values and set stale:true (%s)", reason)

    if count >= MAX_CONSECUTIVE_FAILURES:
        log.error("%d consecutive failures — failing the workflow", count)
        return 1
    return 0


# --------------------------------------------------------------------------
# エントリポイント
# --------------------------------------------------------------------------

def run(data_dir: Path, session=None, sleeper=None, now: dt.datetime | None = None) -> int:
    now = now or dt.datetime.now(JST)
    data_dir = Path(data_dir)
    fg_path = data_dir / FG_JSON

    previous = read_json(fg_path)

    kwargs: dict[str, Any] = {}
    if session is not None:
        kwargs["session"] = session
    if sleeper is not None:
        kwargs["sleeper"] = sleeper

    try:
        payload = cnn.fetch(**kwargs)
        cnn.validate(payload, previous_as_of(previous))
        doc = build_document(payload, now=now)
    except CnnError as exc:
        return mark_stale(data_dir, f"{type(exc).__name__}: {exc}", now)
    except (KeyError, TypeError, ValueError) as exc:  # 想定外の構造も同じ扱いにする
        return mark_stale(data_dir, f"{type(exc).__name__}: {exc}", now)

    write_json(fg_path, doc)
    upsert_history_csv(data_dir / HISTORY_CSV, doc)
    _save_failure_state(data_dir / FAILURE_STATE, 0, None, now)

    us = doc["us"]
    log.info(
        "wrote %s: as_of=%s score=%.2f rating=%s delta=%s streak=%d history=%d",
        fg_path, us["as_of"], us["score"], us["rating"], us["delta"], us["streak"],
        len(us["history"]),
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="CNN Fear & Greed → data/fg.json (schema 2)")
    parser.add_argument("--data-dir", default=str(DEFAULT_DATA_DIR), type=Path)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    return run(args.data_dir)


if __name__ == "__main__":
    sys.exit(main())
