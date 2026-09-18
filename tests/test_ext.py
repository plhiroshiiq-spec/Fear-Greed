"""fg.ext — EXT(SOX指数の前日比%) の取得と検証 (SPEC.md 9.2章)。"""

from __future__ import annotations

import datetime as dt
import json

import pytest
import requests

from fg import ext
from fg.ext import ExtError

TODAY = dt.date(2026, 9, 18)
NOW = dt.datetime(2026, 9, 18, 6, 41, 12, tzinfo=ext.JST)

STOOQ_CSV = """Date,Open,High,Low,Close,Volume
2026-09-15,5100.0,5150.0,5080.0,5120.00,0
2026-09-16,5120.0,5200.0,5110.0,5180.00,0
2026-09-17,5180.0,5190.0,5090.0,5102.34,0
"""


def yahoo_payload(closes, start=dt.date(2026, 9, 15)):
    stamps = [
        int(dt.datetime(d.year, d.month, d.day, tzinfo=dt.timezone.utc).timestamp())
        for d in (start + dt.timedelta(days=i) for i in range(len(closes)))
    ]
    return {"chart": {"result": [{"timestamp": stamps,
                                  "indicators": {"quote": [{"close": closes}]}}]}}


class Resp:
    def __init__(self, status_code=200, text="", payload=None):
        self.status_code = status_code
        self.text = text
        self._payload = payload

    def json(self):
        if self._payload is None:
            raise ValueError("not json")
        return self._payload


class Session:
    """URL ごとに返すものをスクリプトで与える。"""

    def __init__(self, stooq=None, yahoo=None):
        self.stooq, self.yahoo = stooq, yahoo
        self.calls = []

    def get(self, url, headers=None, timeout=None):
        self.calls.append(url)
        item = self.stooq if "stooq" in url else self.yahoo
        if item is None:
            raise requests.ConnectionError("blocked")
        if isinstance(item, Exception):
            raise item
        return item


# --- 取得 ----------------------------------------------------------------

def test_stooq_is_tried_first_and_gives_the_change():
    session = Session(stooq=Resp(text=STOOQ_CSV))
    source, as_of, change = ext.fetch(session=session, today=TODAY)
    assert source == "stooq"
    assert as_of == dt.date(2026, 9, 17)
    # 5102.34 / 5180.00 − 1 = −1.4993…%
    assert change == pytest.approx(-1.4993, abs=1e-3)
    assert len(session.calls) == 1   # 1本目で足りたら2本目を叩かない


def test_falls_back_to_yahoo():
    session = Session(stooq=Resp(status_code=503), yahoo=Resp(payload=yahoo_payload([100.0, 102.0])))
    source, _, change = ext.fetch(session=session, today=TODAY)
    assert source == "yahoo"
    assert change == pytest.approx(2.0)


def test_raises_when_all_sources_fail():
    session = Session(stooq=Resp(status_code=500), yahoo=Resp(status_code=500))
    with pytest.raises(ExtError, match="all sources failed"):
        ext.fetch(session=session, today=TODAY)


def test_yahoo_skips_null_closes():
    payload = yahoo_payload([100.0, None, 105.0])
    source, _, change = ext.fetch(session=Session(stooq=Resp(status_code=500), yahoo=Resp(payload=payload)),
                                  today=TODAY)
    assert change == pytest.approx(5.0)


def test_source_with_one_row_is_rejected():
    session = Session(stooq=Resp(text="Date,Close\n2026-09-17,5100.0\n"), yahoo=Resp(status_code=500))
    with pytest.raises(ExtError):
        ext.fetch(session=session, today=TODAY)


# --- 検証 (9.2章 誤った値は絶対に出さない) --------------------------------

def test_validate_rejects_absurd_change():
    with pytest.raises(ExtError, match="out of range"):
        ext.validate(dt.date(2026, 9, 17), 45.0, TODAY)
    ext.validate(dt.date(2026, 9, 17), 29.9, TODAY)


def test_validate_rejects_stale_and_future_dates():
    with pytest.raises(ExtError, match="too old"):
        ext.validate(dt.date(2026, 9, 1), 1.0, TODAY)
    with pytest.raises(ExtError, match="future"):
        ext.validate(dt.date(2026, 9, 19), 1.0, TODAY)


def test_invalid_source_falls_through_to_the_next():
    absurd = "Date,Close\n2026-09-16,100.0\n2026-09-17,900.0\n"   # +800%
    session = Session(stooq=Resp(text=absurd), yahoo=Resp(payload=yahoo_payload([100.0, 101.0])))
    source, _, change = ext.fetch(session=session, today=TODAY)
    assert source == "yahoo"
    assert change == pytest.approx(1.0)


# --- 出力 ----------------------------------------------------------------

def test_run_writes_ext_json(tmp_path):
    assert ext.run(tmp_path, session=Session(stooq=Resp(text=STOOQ_CSV)), now=NOW) == 0
    doc = json.loads((tmp_path / "ext.json").read_text(encoding="utf-8"))
    assert doc["schema"] == 1
    assert doc["ext"]["key"] == "sox"
    assert doc["ext"]["source"] == "stooq"
    assert doc["ext"]["as_of"] == "2026-09-17"
    assert doc["ext"]["change_pct"] == -1.5
    assert doc["ext"]["stale"] is False


def test_only_the_derived_percentage_is_stored(tmp_path):
    """8章と同じ方針。指数の水準そのものは保存しない。"""
    ext.run(tmp_path, session=Session(stooq=Resp(text=STOOQ_CSV)), now=NOW)
    text = (tmp_path / "ext.json").read_text(encoding="utf-8")
    assert "5102.34" not in text and "5180" not in text


def test_failure_keeps_previous_value_and_sets_stale(tmp_path):
    ext.run(tmp_path, session=Session(stooq=Resp(text=STOOQ_CSV)), now=NOW)
    before = json.loads((tmp_path / "ext.json").read_text(encoding="utf-8"))

    rc = ext.run(tmp_path, session=Session(), now=NOW)   # 両ソースとも通信失敗
    after = json.loads((tmp_path / "ext.json").read_text(encoding="utf-8"))

    assert rc == 0                                    # EXTはワークフローを落とさない
    assert after["ext"]["stale"] is True
    assert after["ext"]["change_pct"] == before["ext"]["change_pct"]
    assert after["generated_at"] == before["generated_at"]


def test_failure_without_previous_file_writes_nothing(tmp_path):
    assert ext.run(tmp_path, session=Session(), now=NOW) == 0
    assert not (tmp_path / "ext.json").exists()   # 推測値で埋めない


def test_ext_failure_never_fails_the_workflow(tmp_path):
    for session in (Session(), Session(stooq=Resp(status_code=500), yahoo=Resp(status_code=500))):
        assert ext.run(tmp_path, session=session, now=NOW) == 0
