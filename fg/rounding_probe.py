"""丸め規則の照合ツール (SPEC.md 3章 P1)。

**判定は完了している(切り捨て)。** 本ツールは、CNN が表示の実装を変えたときに
同じ手順で再確認できるように残してある。結論と根拠は docs/rounding.md。

## 照合の考え方

CNN のページはスコアを JavaScript で描画するため、表示値は HTTP 取得では読めない。
そこで JSON 側の値を記録しておき、人が画面の整数を1列だけ書き込む。

比較に使えるのは **確定済みの値** だけである。CNN のページが表示する5つの数値のうち、

| field | 内容 | 比較に使えるか |
|---|---|---|
| ``current`` | 現在値 | **使えない**。API取得と画面読み取りの間に動くため、同一時点である保証がない |
| ``prev_close`` | 前営業日の終値 | 使える(確定済みで動かない) |
| ``w1`` / ``m1`` / ``y1`` | 1週/1か月/1年前 | 使える(同上) |

``current`` も記録はするが ``comparable=no`` とし、判定からは除外する。
確定済みの4値はそれぞれ別の営業日を指すので、**1回の取得で4営業日ぶんの照合**になる。

## 手順

1. ``python -m fg.rounding_probe --observe`` で data/rounding_probe.csv に5行追記する。
2. 人が https://www.cnn.com/markets/fear-and-greed を開き、画面の整数を ``site_display`` に
   書き込む(確定済み4値だけでよい)。
3. ``python -m fg.rounding_probe --decide`` で規則を判定する。
4. 結果を ``fg/rounding.py`` の ``DISPLAY_RULE`` と docs/rounding.md に反映する。
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import logging
import sys
from pathlib import Path

from . import cnn, rounding
from .build_json import DEFAULT_DATA_DIR, JST

log = logging.getLogger("fg.rounding_probe")

PROBE_CSV = "rounding_probe.csv"

#: SPEC.md 3章が求める照合の本数(営業日ぶん)。
REQUIRED_DISCRIMINATING_OBSERVATIONS = 3

HEADER = [
    "as_of",
    "field",
    "observed_at",
    "value",
    "floor",
    "round_half_up",
    "discriminating",
    "comparable",
    "site_display",
    "note",
]

#: (field, CNNのJSONキー, 画面のラベル, 比較に使えるか)
FIELDS = (
    ("current", "score", "メインゲージ", False),
    ("prev_close", "previous_close", "Previous close", True),
    ("w1", "previous_1_week", "1 week ago", True),
    ("m1", "previous_1_month", "1 month ago", True),
    ("y1", "previous_1_year", "1 year ago", True),
)

NOT_COMPARABLE_NOTE = "現在値はAPI取得と画面読み取りの間に動くため判定に使わない"


def _read(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as fh:
        return [row for row in csv.DictReader(fh) if row.get("as_of")]


def _key(row: dict) -> tuple[str, str]:
    return (row["as_of"], row.get("field", "current"))


def _write(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    order = {f[0]: i for i, f in enumerate(FIELDS)}
    with tmp.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=HEADER)
        writer.writeheader()
        for row in sorted(rows, key=lambda r: (r["as_of"], order.get(r.get("field"), 99))):
            writer.writerow({k: row.get(k, "") for k in HEADER})
    tmp.replace(path)


def make_row(as_of: str, field: str, value: float, comparable: bool,
             observed_at: dt.datetime) -> dict:
    cand = rounding.candidates(value)
    return {
        "as_of": as_of,
        "field": field,
        "observed_at": observed_at.isoformat(timespec="seconds"),
        "value": repr(float(value)),
        "floor": str(cand["floor"]),
        "round_half_up": str(cand["round_half_up"]),
        "discriminating": "yes" if rounding.is_discriminating(value) else "no",
        "comparable": "yes" if comparable else "no",
        "site_display": "",
        "note": "" if comparable else NOT_COMPARABLE_NOTE,
    }


def record(path: Path, rows: list[dict]) -> None:
    """観測を追記する。``site_display`` が既に入っていれば消さない。"""
    existing = {_key(r): r for r in _read(path)}
    for row in rows:
        prior = existing.get(_key(row))
        if prior and prior.get("site_display"):
            row["site_display"] = prior["site_display"]
        existing[_key(row)] = row
    _write(path, list(existing.values()))


def rows_from_payload(payload: dict, observed_at: dt.datetime) -> list[dict]:
    fg = payload["fear_and_greed"]
    as_of = (
        cnn.to_utc_date(fg["timestamp"]).isoformat()
        if fg.get("timestamp") is not None
        else cnn.historical_series(payload)[-1][0].isoformat()
    )
    out = []
    for field, cnn_key, _label, comparable in FIELDS:
        value = fg.get(cnn_key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        out.append(make_row(as_of, field, float(value), comparable, observed_at))
    return out


def observe(data_dir: Path, session=None, sleeper=None, now: dt.datetime | None = None) -> int:
    now = now or dt.datetime.now(JST)
    kwargs = {}
    if session is not None:
        kwargs["session"] = session
    if sleeper is not None:
        kwargs["sleeper"] = sleeper
    try:
        payload = cnn.fetch(**kwargs)
        cnn.validate(payload)
    except cnn.CnnError as exc:
        # 観測は補助的な作業なので、失敗してもワークフローは落とさない。
        log.warning("rounding probe skipped: %s", exc)
        return 0

    rows = rows_from_payload(payload, now)
    record(Path(data_dir) / PROBE_CSV, rows)
    log.info("probe %s: %d rows (%d comparable)", rows[0]["as_of"], len(rows),
             sum(1 for r in rows if r["comparable"] == "yes"))
    return 0


def decide(rows: list[dict]) -> dict:
    """埋まっている観測から規則を判定する。``comparable=no`` の行は使わない。"""
    usable, conflicting, skipped = [], [], 0
    for row in rows:
        raw = (row.get("site_display") or "").strip()
        if not raw:
            continue
        if row.get("comparable", "yes") != "yes":
            skipped += 1
            continue
        try:
            shown = int(raw)
        except ValueError:
            conflicting.append({"as_of": row["as_of"], "field": row.get("field"),
                                "reason": f"site_display not an int: {raw!r}"})
            continue
        matches = [r for r in rounding.VALID_RULES if int(row[r]) == shown]
        if not matches:
            conflicting.append(
                {
                    "as_of": row["as_of"],
                    "field": row.get("field"),
                    "reason": f"site showed {shown} but candidates are "
                              f"floor={row['floor']} round_half_up={row['round_half_up']}",
                }
            )
            continue
        usable.append({"as_of": row["as_of"], "field": row.get("field"), "matches": matches,
                       "discriminating": row.get("discriminating") == "yes"})

    survivors = set(rounding.VALID_RULES)
    for item in usable:
        survivors &= set(item["matches"])

    discriminating = sum(1 for i in usable if i["discriminating"])
    decided = (
        len(survivors) == 1
        and not conflicting
        and discriminating >= REQUIRED_DISCRIMINATING_OBSERVATIONS
    )
    return {
        "decided": decided,
        "rule": next(iter(survivors)) if len(survivors) == 1 else None,
        "observations": len(usable),
        "discriminating": discriminating,
        "required": REQUIRED_DISCRIMINATING_OBSERVATIONS,
        "skipped_not_comparable": skipped,
        "survivors": sorted(survivors),
        "conflicts": conflicting,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="丸め規則の照合ツール")
    parser.add_argument("--data-dir", default=str(DEFAULT_DATA_DIR), type=Path)
    parser.add_argument("--observe", action="store_true", help="CNNから1回ぶん観測して追記する")
    parser.add_argument("--decide", action="store_true", help="そろった観測から規則を判定する")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    if args.observe:
        return observe(args.data_dir)
    if args.decide:
        verdict = decide(_read(Path(args.data_dir) / PROBE_CSV))
        print(json.dumps(verdict, ensure_ascii=False, indent=2))
        if verdict["conflicts"]:
            print("\n判別不能: どちらの規則でも説明できない観測がある。"
                  "画面の値とCSVの as_of が同じ時点を指しているか確認すること。", file=sys.stderr)
            return 2
        if not verdict["decided"]:
            print(
                f"\n未確定: 判別可能な照合が {verdict['discriminating']}/{verdict['required']} 件。"
                "data/rounding_probe.csv の site_display 列を埋めること"
                "(comparable=yes の行だけでよい)。",
                file=sys.stderr,
            )
            return 1
        print(f"\n確定: {verdict['rule']} 。fg/rounding.py の DISPLAY_RULE が"
              f" \"{verdict['rule']}\" になっているか確認すること。")
        return 0

    parser.error("--observe か --decide のどちらかを指定すること")
    return 2


if __name__ == "__main__":
    sys.exit(main())
