"""合成ペイロードのビルダー。

CNNの生データはリポジトリに置かない(SPEC.md 8章)ため、テストの入力は
1.2章の構造だけを写した**合成データ**で作る。
"""

from __future__ import annotations

import datetime as dt

EPOCH = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)

COMPONENT_BLOCKS = {
    "market_momentum_sp500": 26.4,
    "market_momentum_sp125": None,  # 参照線。scoreは使わない
    "stock_price_strength": 3.0,
    "stock_price_breadth": 0.2,
    "put_call_options": 36.8,
    "market_volatility_vix": 50.0,
    "market_volatility_vix_50": None,
    "junk_bond_demand": 12.5,
    "safe_haven_demand": 41.1,
}


def ms(date: dt.date, hour: int = 0) -> int:
    moment = dt.datetime(date.year, date.month, date.day, hour, tzinfo=dt.timezone.utc)
    return int((moment - EPOCH).total_seconds() * 1000)


def _series(dates, values, hour=0):
    return [{"x": ms(d, hour), "y": v, "rating": "fear"} for d, v in zip(dates, values)]


def make_payload(
    dates,
    scores,
    *,
    rating="fear",
    previous_close=None,
    duplicate_last=False,
    component_scores=None,
    sp500=5000.0,
    sp125=4825.0,
):
    """1.2章の構造を持つ合成ペイロードを作る。

    ``duplicate_last=True`` で「最終行が当日の現在時刻で重複する」(1.3章)を再現する。
    """
    dates = list(dates)
    scores = list(scores)
    comp = dict(COMPONENT_BLOCKS)
    if component_scores:
        comp.update(component_scores)

    hist = _series(dates, scores)
    if duplicate_last:
        hist.append({"x": ms(dates[-1], 20), "y": scores[-1], "rating": rating})

    payload = {
        "fear_and_greed": {
            "score": scores[-1],
            "rating": rating,
            "timestamp": ms(dates[-1], 20),
            "previous_close": scores[-2] if previous_close is None and len(scores) > 1 else previous_close,
            "previous_1_week": 32.2,
            "previous_1_month": 59.14,
            "previous_1_year": 63.77,
        },
        "fear_and_greed_historical": {
            "timestamp": ms(dates[-1], 20),
            "score": scores[-1],
            "rating": rating,
            "data": hist,
        },
    }
    raw_by_key = {
        "market_momentum_sp500": sp500,
        "market_momentum_sp125": sp125,
        "stock_price_strength": -3.40,
        "stock_price_breadth": 638.17,
        "put_call_options": 0.7597,
        "market_volatility_vix": 18.2,
        "market_volatility_vix_50": 17.0,
        "junk_bond_demand": 2.31,
        "safe_haven_demand": -1.02,
    }
    for key, score in comp.items():
        payload[key] = {
            "timestamp": ms(dates[-1], 20),
            "score": 0.0 if score is None else score,
            "rating": "fear",
            "data": _series(dates, [raw_by_key[key]] * len(dates)),
        }
    return payload


def business_days(end: dt.date, count: int):
    """土日を飛ばした営業日を ``count`` 日ぶん、古い順に返す。"""
    out: list[dt.date] = []
    day = end
    while len(out) < count:
        if day.weekday() < 5:
            out.append(day)
        day -= dt.timedelta(days=1)
    return list(reversed(out))
