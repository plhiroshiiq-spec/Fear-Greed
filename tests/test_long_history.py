"""fg_history.json(SUMI DECK の詳細チャート用の長い推移)。"""

from __future__ import annotations

import datetime as dt
import json

import requests

from fg import build_json, long_history
from synthetic import business_days, make_payload

NOW = dt.datetime(2026, 9, 18, 6, 40, tzinfo=build_json.JST)


class OkSession:
    def __init__(self, payload):
        self.payload = payload
        self.urls: list[str] = []

    def get(self, url, headers=None, timeout=None):
        self.urls.append(url)
        return type("R", (), {"status_code": 200, "json": lambda _self: self.payload})()


class LongDeadSession(OkSession):
    """fg.json 用の URL は通るが、日付付き(長い推移)の URL だけ落ちる。"""

    def get(self, url, headers=None, timeout=None):
        if url.endswith(long_history.START.isoformat()):
            self.urls.append(url)
            raise requests.ConnectionError("network down")
        return super().get(url, headers, timeout)


def test_merge_later_sources_win_and_sorted():
    rows = long_history.merge(
        [["2021-01-04", 40.0], ["2026-09-17", 10.0]],
        [(dt.date(2021, 1, 5), 41.234), (dt.date(2026, 9, 17), 20.0)],
        [["2026-09-17", 30.0]],
    )
    assert rows == [["2021-01-04", 40.0], ["2021-01-05", 41.23], ["2026-09-17", 30.0]]


def test_merge_skips_broken_rows():
    rows = long_history.merge([["x"], [1, 2], ["2021-01-04", True], ["2021-01-05", 1]], [], [])
    assert rows == [["2021-01-05", 1.0]]


def test_build_drops_days_after_as_of():
    fg_doc = {"us": {"as_of": "2026-09-17", "history": [["2026-09-17", 30.0]]}}
    doc = long_history.build(None, [(dt.date(2026, 9, 18), 99.0)], fg_doc, NOW)
    assert doc["us"] == [["2026-09-17", 30.0]]
    assert doc["as_of"] == "2026-09-17"
    assert set(doc) == {"schema", "generated_at", "as_of", "us"}


def test_run_writes_long_history_matching_fg_tail(tmp_path):
    days = business_days(dt.date(2026, 9, 17), 5)
    payload = make_payload(days, [40.0, 38.0, 35.0, 31.0, 29.0])
    session = OkSession(payload)
    assert build_json.run(tmp_path, session=session, sleeper=lambda _: None, now=NOW) == 0
    assert any(u.endswith("2021-01-01") for u in session.urls)

    fg = json.loads((tmp_path / "fg.json").read_text(encoding="utf-8"))
    hist = json.loads((tmp_path / "fg_history.json").read_text(encoding="utf-8"))
    # 末尾は fg.json と同じ値(確定していない 2 本の直しも含めて食い違わない)
    tail = {d: v for d, v in hist["us"]}
    for d, v in fg["us"]["history"]:
        assert tail[d] == v
    # 1 日 1 行
    text = (tmp_path / "fg_history.json").read_text(encoding="utf-8")
    assert text.count("\n") == len(hist["us"]) + 2


def test_long_history_failure_keeps_previous_and_fg_json_still_written(tmp_path):
    (tmp_path / "fg_history.json").write_text(
        json.dumps({"schema": 1, "generated_at": "x", "as_of": "2021-01-05", "us": [["2021-01-04", 40.0]]}),
        encoding="utf-8",
    )
    days = business_days(dt.date(2026, 9, 17), 3)
    payload = make_payload(days, [35.0, 31.0, 29.0])
    rc = build_json.run(tmp_path, session=LongDeadSession(payload), sleeper=lambda _: None, now=NOW)
    assert rc == 0
    assert (tmp_path / "fg.json").exists()
    hist = json.loads((tmp_path / "fg_history.json").read_text(encoding="utf-8"))
    # 前回の古い日は残り、直近は fg.json から足される
    assert hist["us"][0] == ["2021-01-04", 40.0]
    assert len(hist["us"]) >= 2


def test_placeholder_head_is_dropped():
    rows = [["2021-01-04", 50.0], ["2021-01-05", 50.0], ["2021-01-06", 68.9], ["2021-01-07", 50.0]]
    assert long_history.strip_placeholder_head(rows) == [["2021-01-06", 68.9], ["2021-01-07", 50.0]]
    # 1 本だけの 50 は本物かもしれないので残す
    assert long_history.strip_placeholder_head([["2021-01-04", 50.0], ["2021-01-05", 49.0]])[0][1] == 50.0
