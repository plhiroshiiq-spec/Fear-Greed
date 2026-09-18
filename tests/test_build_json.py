"""fg.build_json — fg.json(schema 2)の組み立てと失敗時の挙動 (SPEC.md 2章 / 3章P1 / 4章)。"""

from __future__ import annotations

import csv
import datetime as dt
import json

import pytest
import requests

from fg import build_json, cnn
from synthetic import business_days, make_payload

NOW = dt.datetime(2026, 9, 18, 6, 41, 12, tzinfo=build_json.JST)


class OkSession:
    def __init__(self, payload):
        self.payload = payload

    def get(self, url, headers=None, timeout=None):
        return type("R", (), {"status_code": 200, "json": lambda _self: self.payload})()


class DeadSession:
    def __init__(self):
        self.calls = 0

    def get(self, url, headers=None, timeout=None):
        self.calls += 1
        raise requests.ConnectionError("network down")


@pytest.fixture
def five_days():
    days = business_days(dt.date(2026, 9, 17), 5)
    scores = [35.0, 33.0, 32.0, 31.0, 29.6285714285714]
    return days, make_payload(days, scores, duplicate_last=True)


def build(payload):
    return build_json.build_document(payload, now=NOW)


# --- schema 2 の形 -------------------------------------------------------

def test_document_shape(five_days):
    days, payload = five_days
    doc = build(payload)
    assert doc["schema"] == 2
    assert doc["generated_at"] == "2026-09-18T06:41:12+09:00"
    assert "jp" not in doc  # 2章: jpキーは予約のみ。v2.0では出力しない
    us = doc["us"]
    assert us["source"] == "cnn"
    assert us["as_of"] == days[-1].isoformat()
    assert us["stale"] is False


def test_score_is_cnn_value_not_recomputed(five_days):
    """1.3章: 自前で再計算せず fear_and_greed.score をそのまま使う。"""
    _, payload = five_days
    assert build(payload)["us"]["score"] == 29.63


def test_all_seven_components_have_score(five_days):
    _, payload = five_days
    comps = build(payload)["us"]["components"]
    assert len(comps) == 7
    assert [c["key"] for c in comps] == [
        "momentum", "strength", "breadth", "put_call", "volatility", "junk_bond", "safe_haven",
    ]
    assert all(isinstance(c["score"], float) for c in comps)
    assert all(c["name_ja"] for c in comps)


def test_history_has_no_duplicate_dates(five_days):
    _, payload = five_days
    history = build(payload)["us"]["history"]
    dates = [d for d, _ in history]
    assert len(dates) == len(set(dates))
    assert dates == sorted(dates)


def test_history_is_capped_at_260_business_days():
    days = business_days(dt.date(2026, 9, 17), 400)
    payload = make_payload(days, [50.0 + (i % 7) for i in range(400)])
    history = build(payload)["us"]["history"]
    assert len(history) == 260
    assert history[-1][0] == days[-1].isoformat()


def test_short_history_is_not_padded():
    """5.1章: 足りない分を0や前値で埋めない。"""
    days = business_days(dt.date(2026, 9, 17), 4)
    payload = make_payload(days, [40.0, 39.0, 38.0, 37.0])
    assert len(build(payload)["us"]["history"]) == 4


# --- delta / streak (手計算と一致すること) --------------------------------

def test_delta_matches_hand_calculation():
    days = business_days(dt.date(2026, 9, 17), 3)
    payload = make_payload(days, [40.0, 31.97, 29.63])
    us = build(payload)["us"]
    assert us["prev_close"] == 31.97
    assert us["delta"] == pytest.approx(-2.34)  # 29.63 − 31.97


@pytest.mark.parametrize(
    "scores,expected",
    [
        ([40.0, 38.0, 35.0, 31.0, 29.0], -4),   # 4日連続の下降
        ([29.0, 31.0, 35.0, 38.0, 40.0], 4),    # 4日連続の上昇
        ([40.0, 38.0, 39.0, 41.0, 42.0], 3),    # 直近3日が上昇
        ([40.0, 38.0, 35.0, 31.0, 31.0], 0),    # 横ばいで途切れる
        ([40.0], 0),                            # 1本では判定不能
    ],
)
def test_streak_matches_hand_calculation(scores, expected):
    days = business_days(dt.date(2026, 9, 17), len(scores))
    assert build(make_payload(days, scores))["us"]["streak"] == expected


def test_streak_sign_agrees_with_delta(five_days):
    _, payload = five_days
    us = build(payload)["us"]
    assert us["delta"] < 0 and us["streak"] < 0


# --- 実行(成功系) -------------------------------------------------------

def test_run_writes_fg_json_and_csv(tmp_path, five_days):
    days, payload = five_days
    assert build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW) == 0

    doc = json.loads((tmp_path / "fg.json").read_text(encoding="utf-8"))
    assert doc["us"]["score"] == 29.63
    assert doc["us"]["stale"] is False

    with (tmp_path / "history_us.csv").open(newline="", encoding="utf-8") as fh:
        rows = list(csv.reader(fh))
    assert rows[0] == ["date", "score"] + [c.key for c in cnn.COMPONENTS]
    assert rows[-1][0] == days[-1].isoformat()
    assert rows[-1][1] == "29.63"


def test_csv_upsert_does_not_duplicate_dates(tmp_path, five_days):
    _, payload = five_days
    for _ in range(3):
        build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW)
    with (tmp_path / "history_us.csv").open(newline="", encoding="utf-8") as fh:
        rows = list(csv.reader(fh))[1:]
    dates = [r[0] for r in rows]
    assert len(dates) == len(set(dates)) == 1


def test_no_raw_cnn_payload_is_written(tmp_path, five_days):
    """8章: CNNの生データを保存しない。書き出すのはスコアと派生統計だけ。"""
    _, payload = five_days
    build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW)
    written = sorted(p.name for p in tmp_path.iterdir())
    assert written == ["failure_state.json", "fg.json", "history_us.csv"]

    text = (tmp_path / "fg.json").read_text(encoding="utf-8")
    for cnn_key in ("fear_and_greed_historical", "market_momentum_sp125", "put_call_options"):
        assert cnn_key not in text


# --- 実行(失敗系。4章 手順3〜4) -----------------------------------------

def test_failure_keeps_previous_fg_json_and_sets_stale(tmp_path, five_days):
    _, payload = five_days
    build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW)
    before = json.loads((tmp_path / "fg.json").read_text(encoding="utf-8"))

    rc = build_json.run(tmp_path, session=DeadSession(), sleeper=lambda _: None, now=NOW)
    after = json.loads((tmp_path / "fg.json").read_text(encoding="utf-8"))

    assert rc == 0                                   # 手順3: 終了コード0
    assert after["us"]["stale"] is True
    assert after["us"]["score"] == before["us"]["score"]
    assert after["us"]["history"] == before["us"]["history"]
    assert after["us"]["components"] == before["us"]["components"]
    assert after["generated_at"] == before["generated_at"]  # データの鮮度を偽らない


def test_three_consecutive_failures_fail_the_workflow(tmp_path, five_days):
    _, payload = five_days
    build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW)
    codes = [
        build_json.run(tmp_path, session=DeadSession(), sleeper=lambda _: None, now=NOW)
        for _ in range(3)
    ]
    assert codes == [0, 0, 1]  # 手順4: 3回連続でワークフローを失敗させる


def test_failure_counter_resets_after_success(tmp_path, five_days):
    _, payload = five_days
    build_json.run(tmp_path, session=DeadSession(), sleeper=lambda _: None, now=NOW)
    build_json.run(tmp_path, session=DeadSession(), sleeper=lambda _: None, now=NOW)
    build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW)
    state = json.loads((tmp_path / "failure_state.json").read_text(encoding="utf-8"))
    assert state["consecutive_failures"] == 0


def test_failure_without_previous_file_writes_nothing(tmp_path):
    rc = build_json.run(tmp_path, session=DeadSession(), sleeper=lambda _: None, now=NOW)
    assert rc == 0
    assert not (tmp_path / "fg.json").exists()  # 推測値で埋めない


def test_invalid_payload_is_rejected_and_previous_kept(tmp_path, five_days):
    """4章: 1つでも外れたら採用しない。"""
    _, payload = five_days
    build_json.run(tmp_path, session=OkSession(payload), sleeper=lambda _: None, now=NOW)

    bad = json.loads(json.dumps(payload))
    bad["fear_and_greed"]["score"] = 142.0
    rc = build_json.run(tmp_path, session=OkSession(bad), sleeper=lambda _: None, now=NOW)

    doc = json.loads((tmp_path / "fg.json").read_text(encoding="utf-8"))
    assert rc == 0
    assert doc["us"]["score"] == 29.63
    assert doc["us"]["stale"] is True


def test_history_regression_is_rejected(tmp_path):
    days = business_days(dt.date(2026, 9, 17), 3)
    build_json.run(tmp_path, session=OkSession(make_payload(days, [35.0, 33.0, 31.0])),
                   sleeper=lambda _: None, now=NOW)
    older = business_days(dt.date(2026, 9, 10), 3)
    rc = build_json.run(tmp_path, session=OkSession(make_payload(older, [55.0, 54.0, 53.0])),
                        sleeper=lambda _: None, now=NOW)
    doc = json.loads((tmp_path / "fg.json").read_text(encoding="utf-8"))
    assert rc == 0
    assert doc["us"]["score"] == 31.0
    assert doc["us"]["stale"] is True
