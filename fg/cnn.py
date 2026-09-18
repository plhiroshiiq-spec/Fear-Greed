"""CNN Fear & Greed の取得・リトライ・検証 (SPEC.md 1章 / 4章)。

- 公式APIではない。ブラウザ風 User-Agent と Referer が無いと拒否される(1.1章)。
- 取得した生JSONはリポジトリに保存しない(8章)。呼び出し側もメモリ上でのみ扱う。
"""

from __future__ import annotations

import datetime as dt
import logging
import time
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Sequence

import requests

log = logging.getLogger(__name__)

BASE_URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata"
REFERER = "https://www.cnn.com/markets/fear-and-greed"
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

HEADERS = {
    "User-Agent": USER_AGENT,
    "Referer": REFERER,
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "en-US,en;q=0.9",
    "Origin": "https://www.cnn.com",
}

TIMEOUT = 20
MAX_ATTEMPTS = 3
BACKOFF_BASE = 2.0

#: SPEC.md 1.3章の5区分。CNNは小文字で返す。
VALID_RATINGS = (
    "extreme fear",
    "fear",
    "neutral",
    "greed",
    "extreme greed",
)

RATING_JA = {
    "extreme fear": "極度の恐怖",
    "fear": "恐怖",
    "neutral": "中立",
    "greed": "強欲",
    "extreme greed": "極度の強欲",
}


@dataclass(frozen=True)
class Component:
    key: str
    name_ja: str
    cnn_key: str


#: SPEC.md 1.2章の7要素。sp125 と vix_50 は参照線であり独立要素ではない。
COMPONENTS: Sequence[Component] = (
    Component("momentum", "モメンタム", "market_momentum_sp500"),
    Component("strength", "株価の強さ", "stock_price_strength"),
    Component("breadth", "市場の幅", "stock_price_breadth"),
    Component("put_call", "プット/コール", "put_call_options"),
    Component("volatility", "ボラティリティ", "market_volatility_vix"),
    Component("junk_bond", "ジャンク債需要", "junk_bond_demand"),
    Component("safe_haven", "安全資産需要", "safe_haven_demand"),
)

MOMENTUM_REFERENCE_KEY = "market_momentum_sp125"


class CnnError(Exception):
    """fg.cnn の基底例外。"""


class CnnFetchError(CnnError):
    """ネットワーク/HTTP/JSONパースの失敗。"""


class CnnValidationError(CnnError):
    """取得はできたが内容が信用できない(4章の妥当性検証に不合格)。"""


# --------------------------------------------------------------------------
# 取得
# --------------------------------------------------------------------------

def _get(url: str, session: requests.Session | None = None) -> dict:
    sess = session or requests
    try:
        resp = sess.get(url, headers=HEADERS, timeout=TIMEOUT)
    except requests.RequestException as exc:  # pragma: no cover - 実ネットワーク
        raise CnnFetchError(f"request failed: {url}: {exc}") from exc
    if resp.status_code != 200:
        raise CnnFetchError(f"HTTP {resp.status_code} for {url}")
    try:
        payload = resp.json()
    except ValueError as exc:
        raise CnnFetchError(f"response is not JSON: {url}: {exc}") from exc
    if not isinstance(payload, dict):
        raise CnnFetchError(f"unexpected JSON shape for {url}: {type(payload).__name__}")
    return payload


def _fetch_with_retry(
    url: str,
    session: requests.Session | None = None,
    sleeper: Callable[[float], None] = time.sleep,
    max_attempts: int = MAX_ATTEMPTS,
) -> dict:
    """指数バックオフで最大 ``max_attempts`` 回リトライする(4章 手順1)。"""
    last: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            return _get(url, session=session)
        except CnnFetchError as exc:
            last = exc
            log.warning("fetch failed (%d/%d): %s", attempt, max_attempts, exc)
            if attempt < max_attempts:
                sleeper(BACKOFF_BASE ** attempt)
    assert last is not None
    raise last


def fallback_url(days_back: int = 7, today: dt.date | None = None) -> str:
    """日付付きURL(``graphdata/YYYY-MM-DD``)。指定日〜現在の履歴が返る。"""
    today = today or dt.datetime.now(dt.timezone.utc).date()
    return f"{BASE_URL}/{today - dt.timedelta(days=days_back):%Y-%m-%d}"


def fetch(
    session: requests.Session | None = None,
    sleeper: Callable[[float], None] = time.sleep,
    history_days: int = 400,
    today: dt.date | None = None,
) -> dict:
    """CNNのJSONを取得する。

    4章の手順1〜2をここで実装する。
    1. ``graphdata`` を指数バックオフで3回まで
    2. 失敗したら ``graphdata/YYYY-MM-DD`` にフォールバック(こちらも3回まで)

    どちらも失敗したら :class:`CnnFetchError` を送出する(手順3は呼び出し側)。
    """
    try:
        return _fetch_with_retry(BASE_URL, session=session, sleeper=sleeper)
    except CnnFetchError as primary:
        url = fallback_url(days_back=history_days, today=today)
        log.warning("primary endpoint failed, falling back to %s", url)
        try:
            return _fetch_with_retry(url, session=session, sleeper=sleeper)
        except CnnFetchError as secondary:
            raise CnnFetchError(
                f"primary and dated fallback both failed: {primary} / {secondary}"
            ) from secondary


# --------------------------------------------------------------------------
# 正規化
# --------------------------------------------------------------------------

def to_utc_date(value: Any) -> dt.date:
    """CNNの時刻表現(ms epoch もしくは ISO文字列)を UTC の日付にする。"""
    if isinstance(value, bool):
        raise CnnValidationError(f"unsupported timestamp: {value!r}")
    if isinstance(value, (int, float)):
        return dt.datetime.fromtimestamp(value / 1000.0, tz=dt.timezone.utc).date()
    if isinstance(value, str):
        text = value.strip()
        if text.endswith("Z"):
            text = text[:-1] + "+00:00"
        try:
            parsed = dt.datetime.fromisoformat(text)
        except ValueError:
            try:
                return dt.date.fromisoformat(value.strip()[:10])
            except ValueError as exc:
                raise CnnValidationError(f"unparsable timestamp: {value!r}") from exc
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=dt.timezone.utc)
        return parsed.astimezone(dt.timezone.utc).date()
    raise CnnValidationError(f"unsupported timestamp: {value!r}")


def historical_series(payload: dict) -> list[tuple[dt.date, float]]:
    """``fear_and_greed_historical`` を (日付, スコア) に直し、日付でdedupeする。

    SPEC.md 1.3章: 最終行が当日の現在時刻で重複する。日付でdedupeし、
    同じ日付が複数あれば **後ろ(より新しい)を採用**する。
    """
    block = payload.get("fear_and_greed_historical") or {}
    rows = block.get("data") or []
    by_date: dict[dt.date, float] = {}
    for row in rows:
        if not isinstance(row, dict) or "x" not in row or "y" not in row:
            continue
        y = row["y"]
        if not isinstance(y, (int, float)) or isinstance(y, bool):
            continue
        by_date[to_utc_date(row["x"])] = float(y)
    return [(d, by_date[d]) for d in sorted(by_date)]


def component_series(payload: dict, cnn_key: str) -> list[tuple[dt.date, float]]:
    block = payload.get(cnn_key) or {}
    rows = block.get("data") or []
    by_date: dict[dt.date, float] = {}
    for row in rows:
        if not isinstance(row, dict) or "x" not in row or "y" not in row:
            continue
        y = row["y"]
        if not isinstance(y, (int, float)) or isinstance(y, bool):
            continue
        by_date[to_utc_date(row["x"])] = float(y)
    return [(d, by_date[d]) for d in sorted(by_date)]


def _last_y(payload: dict, cnn_key: str) -> float | None:
    series = component_series(payload, cnn_key)
    return series[-1][1] if series else None


def component_scores(payload: dict) -> list[dict]:
    """7要素の score と raw を取り出す(1.3章: 要素data内の rating は無視する)。

    ``raw`` は要素の素の観測量。モメンタムだけは S&P500 と125日移動平均の
    乖離率(%)という派生統計にする(SPEC.md 2章の例に合わせる)。
    """
    out: list[dict] = []
    for comp in COMPONENTS:
        block = payload.get(comp.cnn_key) or {}
        score = block.get("score")
        if not isinstance(score, (int, float)) or isinstance(score, bool):
            raise CnnValidationError(f"component {comp.key}: score is missing or not a number")
        if comp.key == "momentum":
            raw = _momentum_raw(payload)
        else:
            raw = _last_y(payload, comp.cnn_key)
        out.append(
            {
                "key": comp.key,
                "name_ja": comp.name_ja,
                "score": round(float(score), 1),
                "raw": None if raw is None else round(float(raw), 4),
            }
        )
    return out


def _momentum_raw(payload: dict) -> float | None:
    """S&P500 が125日移動平均から何%乖離しているか。"""
    sp500 = _last_y(payload, "market_momentum_sp500")
    sp125 = _last_y(payload, MOMENTUM_REFERENCE_KEY)
    if sp500 is None or sp125 is None or sp125 == 0:
        return None
    return (sp500 / sp125 - 1.0) * 100.0


# --------------------------------------------------------------------------
# 妥当性検証 (4章)
# --------------------------------------------------------------------------

def validate(payload: dict, previous_as_of: dt.date | None = None) -> None:
    """1つでも外れたら採用しない(4章)。

    - ``score`` が 0〜100 の範囲
    - ``rating`` が既知の5種類のいずれか
    - ``history`` の最新日が前回保存分から後退していない
    - 7要素すべてに ``score`` が入っている(P1検収条件)
    """
    fg = payload.get("fear_and_greed")
    if not isinstance(fg, dict):
        raise CnnValidationError("fear_and_greed block is missing")

    score = fg.get("score")
    if not isinstance(score, (int, float)) or isinstance(score, bool):
        raise CnnValidationError(f"score is not a number: {score!r}")
    if not (0.0 <= float(score) <= 100.0):
        raise CnnValidationError(f"score out of range: {score!r}")

    rating = fg.get("rating")
    if not isinstance(rating, str) or rating.strip().lower() not in VALID_RATINGS:
        raise CnnValidationError(f"unknown rating: {rating!r}")

    history = historical_series(payload)
    if not history:
        raise CnnValidationError("fear_and_greed_historical is empty")

    latest = history[-1][0]
    if previous_as_of is not None and latest < previous_as_of:
        raise CnnValidationError(
            f"history went backwards: latest={latest} previous_as_of={previous_as_of}"
        )

    # 7要素すべてに score があること。欠けていれば例外になる。
    component_scores(payload)
