"""data/fg_history.json — 長い期間の総合スコアの推移 (SUMI DECK の詳細チャート用)。

fg.json の ``history`` は直近 260 営業日だけ。詳細チャート(指でなぞって過去へ戻る)のために、
CNN の日付付き URL(``graphdata/2021-01-01``)で取れる全期間の **日付と総合スコアだけ** を別のファイルに置く。

設計原則(0章)との関係:
- CNNの値をそのまま出す: 総合スコアを丸め(小数2桁)以外で変えない
- CNNの生データは保存しない: 置くのは (日付, 総合スコア) の列だけ。要素の値や CNN の内部キーは置かない
- 壊れても嘘をつかない: 取れなかった日は前回のファイルをそのまま残す。fg.json の作成は止めない

末尾は fg.json の ``history``(確定していない最後の 2 本を CNN 自身の score / previous_close で直したもの)で上書きし、
2 つのファイルで同じ日の値が食い違わないようにする。
"""

from __future__ import annotations

import datetime as dt
import json
import logging
from pathlib import Path
from typing import Any

from . import cnn

log = logging.getLogger("fg.long_history")

FG_HISTORY_JSON = "fg_history.json"
SCHEMA = 1
#: CNN の日付付き URL が返す一番古い日(2026-09-19 実測: 2021-01-01 は通る、2020-01-01 は 500)
START = dt.date(2021, 1, 1)


def history_url(start: dt.date = START) -> str:
    return f"{cnn.BASE_URL}/{start:%Y-%m-%d}"


def merge(
    previous: list[list[Any]] | None,
    fetched: list[tuple[dt.date, float]],
    recent: list[list[Any]],
) -> list[list[Any]]:
    """前回の列・今回取った列・fg.json の直近の列を日付で合わせる。後ろほど優先。古い順に返す。"""
    by_date: dict[str, float] = {}
    for rows in (previous or [], [[d.isoformat(), v] for d, v in fetched], recent):
        for row in rows:
            if not isinstance(row, (list, tuple)) or len(row) < 2:
                continue
            date, value = row[0], row[1]
            if not isinstance(date, str) or not isinstance(value, (int, float)) or isinstance(value, bool):
                continue
            by_date[date] = round(float(value), 2)
    return [[d, by_date[d]] for d in sorted(by_date)]


#: CNN の一番古い何日かは値が無く、ちょうど 50 で埋められている(2026-09-19 実測: 2021-01-04〜01-21 の 13 日)
PLACEHOLDER = 50.0


def strip_placeholder_head(rows: list[list[Any]]) -> list[list[Any]]:
    """先頭に続くちょうど 50 の列(値の無い日の埋め草)を落とす。推測値を実際の値として出さない(設計原則1)。"""
    i = 0
    while i < len(rows) and rows[i][1] == PLACEHOLDER:
        i += 1
    return rows[i:] if i >= 2 else rows


def build(previous_doc: dict | None, fetched: list[tuple[dt.date, float]], fg_doc: dict, now: dt.datetime) -> dict:
    us = fg_doc.get("us") or {}
    previous_rows = (previous_doc or {}).get("us") if isinstance(previous_doc, dict) else None
    rows = merge(previous_rows if isinstance(previous_rows, list) else None, fetched, us.get("history") or [])
    # fg.json の as_of より先の日は持たない(確定していない値を増やさない)
    as_of = us.get("as_of")
    if isinstance(as_of, str):
        rows = [r for r in rows if r[0] <= as_of]
    rows = strip_placeholder_head(rows)
    return {
        "schema": SCHEMA,
        "generated_at": now.isoformat(timespec="seconds"),
        "as_of": as_of,
        "us": rows,
    }


def write_compact(path: Path, doc: dict) -> None:
    """1 日 1 行で書く(indent=2 だと 1 日が 4 行になり、5 年で 6,000 行近くになる)。"""
    head = {k: v for k, v in doc.items() if k != "us"}
    lines = [json.dumps(row, ensure_ascii=False, separators=(",", ":")) for row in doc["us"]]
    text = json.dumps(head, ensure_ascii=False, separators=(",", ":"))[:-1]
    text += ',"us":[\n' + ",\n".join(lines) + "\n]}\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    tmp.replace(path)


def update(data_dir: Path, fg_doc: dict, now: dt.datetime, session=None, sleeper=None) -> bool:
    """長い推移を取り直して書く。失敗しても例外にしない(fg.json の作成を止めない)。書いたら True。"""
    from .build_json import read_json  # 循環 import を避ける

    path = Path(data_dir) / FG_HISTORY_JSON
    previous = read_json(path)
    kwargs: dict[str, Any] = {}
    if session is not None:
        kwargs["session"] = session
    if sleeper is not None:
        kwargs["sleeper"] = sleeper
    try:
        payload = cnn._fetch_with_retry(history_url(), **kwargs)
        fetched = cnn.historical_series(payload)
    except Exception as exc:  # noqa: BLE001 — ここで止めない
        log.warning("long history fetch failed; keeping previous %s (%s: %s)", path.name, type(exc).__name__, exc)
        fetched = []
    if not fetched and previous is None and not (fg_doc.get("us") or {}).get("history"):
        return False
    doc = build(previous, fetched, fg_doc, now)
    write_compact(path, doc)
    log.info("wrote %s: %d days (%s .. %s)", path, len(doc["us"]),
             doc["us"][0][0] if doc["us"] else "-", doc["us"][-1][0] if doc["us"] else "-")
    return True
