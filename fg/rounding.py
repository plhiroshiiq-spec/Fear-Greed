"""表示用の丸め規則 — 唯一の定義箇所。

SPEC.md 1.3章:
    値の精度 … 小数のfloatで配信(例 29.6285714…)
    表示の丸め規則はP1で複数日照合して確定。

**P1で確定済み: 切り捨て(floor)**。2026-09-18 に CNN画面の
「Previous close / 1 week ago / 1 month ago / 1 year ago」の4値(28.69→28,
32.69→32, 54.63→54, 66.54→66)と照合して確定した。4つとも小数部が0.5以上で
判別可能であり、4つとも切り捨てだった。根拠の全文は docs/rounding.md。

``DISPLAY_RULE`` を書き換えるだけで engine 側の表示が揃う。
SUMI DECK(Kotlin)側も同じ規則を実装すること。
"""

from __future__ import annotations

import math
from decimal import ROUND_HALF_UP, Decimal

#: "round_half_up"(四捨五入) または "floor"(切り捨て)。
#: 2026-09-18 に実測で "floor"(切り捨て)と確定した。docs/rounding.md を参照。
#: SUMI DECK(Kotlin)側もこの規則に合わせること。
DISPLAY_RULE = "floor"

VALID_RULES = ("round_half_up", "floor")


def floor_int(score: float) -> int:
    """切り捨て。"""
    return int(math.floor(score))


def round_half_up_int(score: float) -> int:
    """四捨五入(0.5 は常に上へ。Pythonの銀行丸めを避ける)。"""
    return int(Decimal(repr(float(score))).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def display_int(score: float, rule: str = None) -> int:
    """CNN画面と揃える整数表示値を返す。"""
    rule = DISPLAY_RULE if rule is None else rule
    if rule == "floor":
        return floor_int(score)
    if rule == "round_half_up":
        return round_half_up_int(score)
    raise ValueError(f"unknown rounding rule: {rule!r}")


def candidates(score: float) -> dict:
    """照合用に両方の候補を返す。"""
    return {
        "floor": floor_int(score),
        "round_half_up": round_half_up_int(score),
    }


def is_discriminating(score: float) -> bool:
    """この値で四捨五入と切り捨てを区別できるか(小数部 >= 0.5 のとき区別できる)。"""
    c = candidates(score)
    return c["floor"] != c["round_half_up"]
