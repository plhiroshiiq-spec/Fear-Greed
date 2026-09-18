"""fg.cnn の取得・正規化・検証 (SPEC.md 1章 / 4章)。"""

from __future__ import annotations

import datetime as dt

import pytest
import requests

from fg import cnn
from fg.cnn import CnnFetchError, CnnValidationError

from synthetic import business_days, make_payload, ms


class FakeResponse:
    def __init__(self, status_code=200, payload=None, text="{}"):
        self.status_code = status_code
        self._payload = payload
        self._text = text

    def json(self):
        if self._payload is None:
            raise ValueError("not json")
        return self._payload


class FakeSession:
    """``get`` の結果をスクリプトで与えるセッション。"""

    def __init__(self, script):
        self.script = list(script)
        self.calls: list[str] = []

    def get(self, url, headers=None, timeout=None):
        self.calls.append(url)
        item = self.script.pop(0) if self.script else self.script_default()
        if isinstance(item, Exception):
            raise item
        return item

    @staticmethod
    def script_default():
        return requests.ConnectionError("exhausted")


@pytest.fixture
def sample():
    days = business_days(dt.date(2026, 9, 17), 5)
    return days, make_payload(days, [35.0, 33.0, 32.0, 31.0, 29.6285714285714])


# --- 取得 ----------------------------------------------------------------

def test_fetch_sends_browser_headers(sample):
    _, payload = sample
    captured = {}

    class Session:
        def get(self, url, headers=None, timeout=None):
            captured["url"] = url
            captured["headers"] = headers
            return FakeResponse(payload=payload)

    cnn.fetch(session=Session(), sleeper=lambda _: None)
    assert captured["url"] == cnn.BASE_URL
    assert captured["headers"]["Referer"] == "https://www.cnn.com/markets/fear-and-greed"
    assert "Mozilla" in captured["headers"]["User-Agent"]


def test_fetch_retries_three_times_with_exponential_backoff(sample):
    _, payload = sample
    session = FakeSession([
        requests.ConnectionError("boom"),
        requests.ConnectionError("boom"),
        FakeResponse(payload=payload),
    ])
    waits: list[float] = []
    cnn.fetch(session=session, sleeper=waits.append)
    assert len(session.calls) == 3
    assert waits == [2.0, 4.0]  # 指数バックオフ


def test_fetch_falls_back_to_dated_url(sample):
    _, payload = sample
    session = FakeSession([
        FakeResponse(status_code=418),
        FakeResponse(status_code=418),
        FakeResponse(status_code=418),
        FakeResponse(payload=payload),
    ])
    cnn.fetch(session=session, sleeper=lambda _: None, today=dt.date(2026, 9, 18), history_days=400)
    assert session.calls[:3] == [cnn.BASE_URL] * 3
    assert session.calls[3].startswith(cnn.BASE_URL + "/")


def test_fetch_raises_when_both_endpoints_fail():
    session = FakeSession([requests.ConnectionError("x")] * 6)
    with pytest.raises(CnnFetchError):
        cnn.fetch(session=session, sleeper=lambda _: None)
    assert len(session.calls) == 6  # 本体3回 + 日付付き3回


# --- 正規化 --------------------------------------------------------------

def test_historical_series_dedupes_by_date(sample):
    days, _ = sample
    payload = make_payload(days, [35.0, 33.0, 32.0, 31.0, 29.63], duplicate_last=True)
    series = cnn.historical_series(payload)
    dates = [d for d, _ in series]
    assert len(dates) == len(set(dates)) == 5
    assert dates[-1] == days[-1]


def test_historical_series_keeps_latest_value_for_duplicated_day():
    days = business_days(dt.date(2026, 9, 17), 2)
    payload = make_payload(days, [31.0, 29.0])
    payload["fear_and_greed_historical"]["data"].append(
        {"x": ms(days[-1], 20), "y": 30.5, "rating": "fear"}
    )
    assert cnn.historical_series(payload)[-1] == (days[-1], 30.5)


@pytest.mark.parametrize(
    "value,expected",
    [
        (1789603200000, dt.date(2026, 9, 17)),
        ("2026-09-17T20:00:00+00:00", dt.date(2026, 9, 17)),
        ("2026-09-17T20:00:00Z", dt.date(2026, 9, 17)),
        ("2026-09-17", dt.date(2026, 9, 17)),
    ],
)
def test_to_utc_date_accepts_both_shapes(value, expected):
    assert cnn.to_utc_date(value) == expected


def test_component_scores_has_all_seven(sample):
    _, payload = sample
    comps = cnn.component_scores(payload)
    assert [c["key"] for c in comps] == [c.key for c in cnn.COMPONENTS]
    assert all(isinstance(c["score"], float) for c in comps)


def test_momentum_raw_is_deviation_from_125day_average(sample):
    days, _ = sample
    payload = make_payload(days, [30.0] * 5, sp500=5000.0, sp125=4825.0)
    momentum = cnn.component_scores(payload)[0]
    assert momentum["key"] == "momentum"
    assert momentum["raw"] == pytest.approx(3.6269, abs=1e-4)


def test_component_rating_is_ignored(sample):
    """1.3章: 要素data内の rating は信用できない。出力に混ぜない。"""
    _, payload = sample
    assert all("rating" not in c for c in cnn.component_scores(payload))


# --- 妥当性検証 (4章) ----------------------------------------------------

def test_validate_accepts_good_payload(sample):
    _, payload = sample
    cnn.validate(payload)


@pytest.mark.parametrize("bad", [-0.1, 100.1, "29", None])
def test_validate_rejects_score_out_of_range(sample, bad):
    _, payload = sample
    payload["fear_and_greed"]["score"] = bad
    with pytest.raises(CnnValidationError):
        cnn.validate(payload)


def test_validate_rejects_unknown_rating(sample):
    _, payload = sample
    payload["fear_and_greed"]["rating"] = "panic"
    with pytest.raises(CnnValidationError):
        cnn.validate(payload)


@pytest.mark.parametrize("rating", list(cnn.VALID_RATINGS))
def test_validate_accepts_all_five_ratings(sample, rating):
    _, payload = sample
    payload["fear_and_greed"]["rating"] = rating
    cnn.validate(payload)


def test_validate_rejects_history_going_backwards(sample):
    days, payload = sample
    with pytest.raises(CnnValidationError, match="backwards"):
        cnn.validate(payload, previous_as_of=days[-1] + dt.timedelta(days=1))


def test_validate_allows_same_day_rerun(sample):
    days, payload = sample
    cnn.validate(payload, previous_as_of=days[-1])


def test_validate_rejects_missing_component_score(sample):
    _, payload = sample
    payload["put_call_options"].pop("score")
    with pytest.raises(CnnValidationError, match="put_call"):
        cnn.validate(payload)


def test_validate_rejects_empty_history(sample):
    _, payload = sample
    payload["fear_and_greed_historical"]["data"] = []
    with pytest.raises(CnnValidationError):
        cnn.validate(payload)
