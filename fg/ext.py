"""EXT — 寄り前の外部環境を1つだけ (SPEC.md 9.2章)。

既定は **SOX指数(フィラデルフィア半導体株指数)の前日比%**。

取得はこの engine (GitHub Actions) が行い、``data/ext.json`` に**前日比%だけ**を書く。
SUMI DECK は既に読んでいる raw URL をもう1本読むだけでよく、アプリに新しい依存が増えない。
指数の水準そのものは保存せず、派生統計だけを置く(SPEC.md 8章と同じ方針)。

失敗時の挙動(9.2章「取得失敗時は…誤った値は絶対に出さない」):
1. ソースを順に試す
2. すべて失敗 → **既存の ext.json を上書きせず** ``stale:true`` だけを立てる
3. **ワークフローは落とさない**(EXTは任意機能。これで fg.json の更新を巻き込まない)
"""

from __future__ import annotations

import argparse
import datetime as dt
import logging
import sys
from pathlib import Path
from typing import Callable

import requests

from .build_json import DEFAULT_DATA_DIR, JST, read_json, write_json

log = logging.getLogger("fg.ext")

EXT_JSON = "ext.json"
SCHEMA = 1

KEY = "sox"
NAME_JA = "SOX指数"

TIMEOUT = 20

#: 妥当性検証。1日でこれを超える変化は指数では起きない。外れたら採用しない。
MAX_ABS_CHANGE_PCT = 30.0

#: 取得できた最新日がこれより古ければ採用しない(連休を考慮して広めに取る)。
MAX_AGE_DAYS = 7

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/csv, application/json, */*",
}


class ExtError(Exception):
    """EXTの取得・検証の失敗。"""


# --------------------------------------------------------------------------
# ソース
# --------------------------------------------------------------------------

def _get(url: str, session=None) -> requests.Response:
    sess = session or requests
    try:
        resp = sess.get(url, headers=HEADERS, timeout=TIMEOUT)
    except requests.RequestException as exc:  # pragma: no cover - 実ネットワーク
        raise ExtError(f"request failed: {url}: {exc}") from exc
    if resp.status_code != 200:
        raise ExtError(f"HTTP {resp.status_code} for {url}")
    return resp


def from_stooq(session=None) -> tuple[dt.date, float]:
    """stooq の日足CSV。APIキー不要。``Date,Open,High,Low,Close,Volume``。"""
    text = _get("https://stooq.com/q/d/l/?s=%5Esox&i=d", session=session).text
    rows = [r for r in (line.strip() for line in text.splitlines()) if r]
    if len(rows) < 3:
        raise ExtError("stooq: not enough rows")
    header = [c.strip().lower() for c in rows[0].split(",")]
    try:
        date_at, close_at = header.index("date"), header.index("close")
    except ValueError as exc:
        raise ExtError(f"stooq: unexpected header {header}") from exc

    parsed: list[tuple[dt.date, float]] = []
    for row in rows[1:]:
        cells = row.split(",")
        if len(cells) <= max(date_at, close_at):
            continue
        try:
            parsed.append((dt.date.fromisoformat(cells[date_at]), float(cells[close_at])))
        except ValueError:
            continue
    if len(parsed) < 2:
        raise ExtError("stooq: fewer than 2 usable rows")
    parsed.sort()
    return _change(parsed)


def from_yahoo(session=None) -> tuple[dt.date, float]:
    """Yahoo Finance のチャートAPI(非公式)。APIキー不要。"""
    url = "https://query1.finance.yahoo.com/v8/finance/chart/%5ESOX?range=1mo&interval=1d"
    payload = _get(url, session=session).json()
    try:
        result = payload["chart"]["result"][0]
        stamps = result["timestamp"]
        closes = result["indicators"]["quote"][0]["close"]
    except (KeyError, IndexError, TypeError) as exc:
        raise ExtError(f"yahoo: unexpected shape: {exc}") from exc

    parsed: list[tuple[dt.date, float]] = []
    for ts, close in zip(stamps, closes):
        if close is None or not isinstance(close, (int, float)):
            continue
        parsed.append((dt.datetime.fromtimestamp(ts, tz=dt.timezone.utc).date(), float(close)))
    if len(parsed) < 2:
        raise ExtError("yahoo: fewer than 2 usable rows")
    parsed.sort()
    return _change(parsed)


#: 順に試す。どちらも無料・APIキー不要(docs/ext_source.md)。
SOURCES: list[tuple[str, Callable]] = [
    ("stooq", from_stooq),
    ("yahoo", from_yahoo),
]


def _change(series: list[tuple[dt.date, float]]) -> tuple[dt.date, float]:
    """(最新日, 前日比%) を返す。"""
    (_, prev_close), (latest_date, latest_close) = series[-2], series[-1]
    if prev_close == 0:
        raise ExtError("previous close is zero")
    return latest_date, (latest_close / prev_close - 1.0) * 100.0


# --------------------------------------------------------------------------
# 検証
# --------------------------------------------------------------------------

def validate(as_of: dt.date, change_pct: float, today: dt.date) -> None:
    """1つでも外れたら採用しない。誤った値を出さないことが最優先(9.2章)。"""
    if as_of > today:
        raise ExtError(f"as_of is in the future: {as_of}")
    if (today - as_of).days > MAX_AGE_DAYS:
        raise ExtError(f"as_of is too old: {as_of} (today={today})")
    if abs(change_pct) > MAX_ABS_CHANGE_PCT:
        raise ExtError(f"change_pct out of range: {change_pct}")


def fetch(session=None, today: dt.date | None = None) -> tuple[str, dt.date, float]:
    """使えた最初のソースの (名前, 最新日, 前日比%) を返す。"""
    today = today or dt.datetime.now(dt.timezone.utc).date()
    failures = []
    for name, source in SOURCES:
        try:
            as_of, change = source(session=session)
            validate(as_of, change, today)
            return name, as_of, change
        except ExtError as exc:
            log.warning("ext source %s failed: %s", name, exc)
            failures.append(f"{name}: {exc}")
    raise ExtError("all sources failed: " + " / ".join(failures))


# --------------------------------------------------------------------------
# 出力
# --------------------------------------------------------------------------

def build_document(source: str, as_of: dt.date, change_pct: float, now: dt.datetime) -> dict:
    return {
        "schema": SCHEMA,
        "generated_at": now.isoformat(timespec="seconds"),
        "ext": {
            "key": KEY,
            "name_ja": NAME_JA,
            "source": source,
            "as_of": as_of.isoformat(),
            "change_pct": round(change_pct, 2),
            "stale": False,
        },
    }


def mark_stale(path: Path, reason: str) -> None:
    """既存の ext.json を上書きせず stale だけ立てる。無ければ何も書かない。"""
    doc = read_json(path)
    if doc is None:
        log.warning("ext fetch failed and no previous ext.json exists: %s", reason)
        return
    doc.setdefault("ext", {})["stale"] = True
    write_json(path, doc)
    log.warning("ext fetch failed; kept previous value and set stale:true (%s)", reason)


def run(data_dir: Path, session=None, now: dt.datetime | None = None) -> int:
    """EXTは任意機能なので、失敗しても常に終了コード0で終わる。"""
    now = now or dt.datetime.now(JST)
    path = Path(data_dir) / EXT_JSON
    try:
        source, as_of, change = fetch(session=session, today=now.astimezone(dt.timezone.utc).date())
    except ExtError as exc:
        mark_stale(path, str(exc))
        return 0

    write_json(path, build_document(source, as_of, change, now))
    log.info("wrote %s: source=%s as_of=%s change=%+.2f%%", path, source, as_of, change)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="SOX指数の前日比% → data/ext.json (SPEC.md 9.2章)")
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
