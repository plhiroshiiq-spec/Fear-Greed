"""丸め規則と照合ツール (SPEC.md 1.3章 / 3章P1)。"""

from __future__ import annotations

import datetime as dt

import pytest

from fg import rounding
from fg.rounding_probe import HEADER, _read, decide, record

JST = dt.timezone(dt.timedelta(hours=9))
NOW = dt.datetime(2026, 9, 18, 6, 41, tzinfo=JST)


@pytest.mark.parametrize(
    "score,floor_v,round_v",
    [
        (29.6285714285714, 29, 30),
        (29.4999, 29, 29),
        (29.5, 29, 30),
        (30.0, 30, 30),
        (0.2, 0, 0),
        (99.7, 99, 100),
    ],
)
def test_candidates(score, floor_v, round_v):
    assert rounding.candidates(score) == {"floor": floor_v, "round_half_up": round_v}


def test_round_half_up_is_not_bankers_rounding():
    """Python組み込みの round() は 2.5→2 になる。表示規則としては使えない。"""
    assert round(2.5) == 2
    assert rounding.round_half_up_int(2.5) == 3
    assert rounding.round_half_up_int(3.5) == 4


def test_default_rule_is_the_spec_interim_value():
    """1.3章: 確定するまでは四捨五入とする。"""
    assert rounding.DISPLAY_RULE == "round_half_up"


def test_display_int_honours_rule():
    assert rounding.display_int(29.63, "floor") == 29
    assert rounding.display_int(29.63, "round_half_up") == 30


def test_unknown_rule_raises():
    with pytest.raises(ValueError):
        rounding.display_int(29.63, "ceil")


def test_is_discriminating():
    assert rounding.is_discriminating(29.63) is True
    assert rounding.is_discriminating(29.30) is False


# --- 観測ファイル --------------------------------------------------------

def test_record_appends_and_dedupes_by_date(tmp_path):
    path = tmp_path / "rounding_probe.csv"
    record(path, "2026-09-17", 29.6285714285714, NOW)
    record(path, "2026-09-17", 29.7, NOW)
    record(path, "2026-09-18", 31.2, NOW)
    rows = _read(path)
    assert [r["as_of"] for r in rows] == ["2026-09-17", "2026-09-18"]
    assert list(rows[0]) == HEADER
    assert rows[0]["floor"] == "29" and rows[0]["round_half_up"] == "30"
    assert rows[1]["discriminating"] == "no"  # 31.2 では判別できない


def test_record_preserves_hand_entered_site_display(tmp_path):
    path = tmp_path / "rounding_probe.csv"
    record(path, "2026-09-17", 29.63, NOW)
    rows = _read(path)
    rows[0]["site_display"] = "29"
    from fg.rounding_probe import _write
    _write(path, rows)
    record(path, "2026-09-17", 29.64, NOW)  # 同じ日を取り直しても消えない
    assert _read(path)[0]["site_display"] == "29"


# --- 判定 ----------------------------------------------------------------

def rows_for(entries):
    out = []
    for as_of, score, shown in entries:
        cand = rounding.candidates(score)
        out.append(
            {
                "as_of": as_of,
                "observed_at": NOW.isoformat(),
                "score": repr(score),
                "floor": str(cand["floor"]),
                "round_half_up": str(cand["round_half_up"]),
                "discriminating": "yes" if rounding.is_discriminating(score) else "no",
                "site_display": "" if shown is None else str(shown),
            }
        )
    return out


def test_decide_needs_three_discriminating_days():
    verdict = decide(rows_for([
        ("2026-09-15", 29.63, 29),
        ("2026-09-16", 30.71, 30),
    ]))
    assert verdict["decided"] is False
    assert verdict["discriminating_days"] == 2
    assert verdict["survivors"] == ["floor"]


def test_decide_confirms_floor():
    verdict = decide(rows_for([
        ("2026-09-15", 29.63, 29),
        ("2026-09-16", 30.71, 30),
        ("2026-09-17", 44.52, 44),
        ("2026-09-18", 51.10, 51),  # 判別不能な日が混ざっても矛盾しない
    ]))
    assert verdict["decided"] is True
    assert verdict["rule"] == "floor"
    assert verdict["discriminating_days"] == 3


def test_decide_confirms_round_half_up():
    verdict = decide(rows_for([
        ("2026-09-15", 29.63, 30),
        ("2026-09-16", 30.71, 31),
        ("2026-09-17", 44.52, 45),
    ]))
    assert verdict["decided"] is True
    assert verdict["rule"] == "round_half_up"


def test_decide_reports_conflict_when_neither_rule_explains_it():
    verdict = decide(rows_for([
        ("2026-09-15", 29.63, 29),
        ("2026-09-16", 30.71, 31),  # 矛盾
        ("2026-09-17", 44.52, 44),
    ]))
    assert verdict["decided"] is False
    assert verdict["survivors"] == []


def test_decide_ignores_rows_without_site_display():
    verdict = decide(rows_for([("2026-09-15", 29.63, None)]))
    assert verdict["observations"] == 0
    assert verdict["decided"] is False
