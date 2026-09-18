"""丸め規則の照合ツール (SPEC.md 3章 P1)。

CNNサイトの**表示値(整数)**と JSON の ``score``(小数)を営業日ごとに突き合わせ、
四捨五入か切り捨てかを判定する。

CNNのページはスコアをJavaScriptで描画するため、表示値はHTTP取得では読めない。
そこで2段構えにする。

1. ``--observe``  … JSONの ``score`` と両方の候補値を data/rounding_probe.csv に追記する。
                    GitHub Actions の毎日のジョブから自動で呼ばれる。
2. 人が https://www.cnn.com/markets/fear-and-greed を開き、表示されている整数を
   同じ行の ``site_display`` 列に書き込む(1日5秒)。
3. ``--decide``   … 判別可能な営業日が3日分そろったら規則を確定し、結果を表示する。

``floor`` と ``round_half_up`` が同じ値になる日(小数部 < 0.5)は判別に使えない。
``discriminating`` 列が ``yes`` の日だけが証拠になる。
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
REQUIRED_DISCRIMINATING_DAYS = 3

HEADER = [
    "as_of",
    "observed_at",
    "score",
    "floor",
    "round_half_up",
    "discriminating",
    "site_display",
]


def _read(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as fh:
        return [row for row in csv.DictReader(fh) if row.get("as_of")]


def _write(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=HEADER)
        writer.writeheader()
        for row in sorted(rows, key=lambda r: r["as_of"]):
            writer.writerow({k: row.get(k, "") for k in HEADER})
    tmp.replace(path)


def record(path: Path, as_of: str, score: float, observed_at: dt.datetime) -> dict:
    """1営業日ぶんの観測を追記する。``site_display`` が既に入っていれば消さない。"""
    rows = _read(path)
    cand = rounding.candidates(score)
    row = {
        "as_of": as_of,
        "observed_at": observed_at.isoformat(timespec="seconds"),
        "score": repr(float(score)),
        "floor": str(cand["floor"]),
        "round_half_up": str(cand["round_half_up"]),
        "discriminating": "yes" if rounding.is_discriminating(score) else "no",
        "site_display": "",
    }
    existing = {r["as_of"]: r for r in rows}
    if as_of in existing and existing[as_of].get("site_display"):
        row["site_display"] = existing[as_of]["site_display"]
    existing[as_of] = row
    _write(path, list(existing.values()))
    return row


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

    fg = payload["fear_and_greed"]
    as_of = (
        cnn.to_utc_date(fg["timestamp"]).isoformat()
        if fg.get("timestamp") is not None
        else cnn.historical_series(payload)[-1][0].isoformat()
    )
    row = record(Path(data_dir) / PROBE_CSV, as_of, float(fg["score"]), now)
    log.info(
        "probe %s: score=%s floor=%s round=%s discriminating=%s",
        row["as_of"], row["score"], row["floor"], row["round_half_up"], row["discriminating"],
    )
    return 0


def decide(rows: list[dict]) -> dict:
    """埋まっている観測から規則を判定する。"""
    usable, conflicting = [], []
    for row in rows:
        raw = (row.get("site_display") or "").strip()
        if not raw:
            continue
        try:
            shown = int(raw)
        except ValueError:
            conflicting.append({"as_of": row["as_of"], "reason": f"site_display not an int: {raw!r}"})
            continue
        matches = [r for r in rounding.VALID_RULES if int(row[r]) == shown]
        if not matches:
            conflicting.append(
                {
                    "as_of": row["as_of"],
                    "reason": f"site showed {shown} but candidates are "
                              f"floor={row['floor']} round_half_up={row['round_half_up']}",
                }
            )
            continue
        usable.append({"as_of": row["as_of"], "matches": matches,
                       "discriminating": row.get("discriminating") == "yes"})

    survivors = set(rounding.VALID_RULES)
    for item in usable:
        survivors &= set(item["matches"])

    discriminating_days = sum(1 for i in usable if i["discriminating"])
    decided = (
        len(survivors) == 1
        and not conflicting
        and discriminating_days >= REQUIRED_DISCRIMINATING_DAYS
    )
    return {
        "decided": decided,
        "rule": next(iter(survivors)) if len(survivors) == 1 else None,
        "observations": len(usable),
        "discriminating_days": discriminating_days,
        "required_discriminating_days": REQUIRED_DISCRIMINATING_DAYS,
        "survivors": sorted(survivors),
        "conflicts": conflicting,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", default=str(DEFAULT_DATA_DIR), type=Path)
    parser.add_argument("--observe", action="store_true", help="CNNから1日ぶん観測して追記する")
    parser.add_argument("--decide", action="store_true", help="そろった観測から規則を判定する")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    if args.observe:
        return observe(args.data_dir)
    if args.decide:
        verdict = decide(_read(Path(args.data_dir) / PROBE_CSV))
        print(json.dumps(verdict, ensure_ascii=False, indent=2))
        if verdict["conflicts"]:
            print("\n判別不能: どちらの規則でも説明できない日がある。"
                  "CNNが表示タイミング違いの値を出していないか確認すること。", file=sys.stderr)
            return 2
        if not verdict["decided"]:
            print(
                f"\n未確定: 判別可能な営業日が {verdict['discriminating_days']}/"
                f"{REQUIRED_DISCRIMINATING_DAYS} 日。"
                "data/rounding_probe.csv の site_display 列を埋めること。",
                file=sys.stderr,
            )
            return 1
        print(f"\n確定: {verdict['rule']} 。fg/rounding.py の DISPLAY_RULE を"
              f" \"{verdict['rule']}\" にし、docs/rounding.md に記録すること。")
        return 0

    parser.error("--observe か --decide のどちらかを指定すること")
    return 2


if __name__ == "__main__":
    sys.exit(main())
