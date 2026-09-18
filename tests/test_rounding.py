"""丸め規則と照合ツール (SPEC.md 1.3章 / 3章P1)。"""

from __future__ import annotations

import datetime as dt

import pytest

from fg import rounding
from fg.rounding_probe import HEADER, _read, _write, decide, make_row, record

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


def test_default_rule_is_the_confirmed_one():
    """P1で確定した規則。docs/rounding.md の根拠と一致していること。"""
    assert rounding.DISPLAY_RULE == "floor"


@pytest.mark.parametrize(
    "value,shown",
    [
        (28.69, 28),   # Previous close
        (32.69, 32),   # 1 week ago
        (54.63, 54),   # 1 month ago
        (66.54, 66),   # 1 year ago
        (29.6285714285714, 29),  # SPEC.md 1.3章の観測
    ],
)
def test_confirmed_rule_reproduces_the_observed_cnn_display(value, shown):
    """2026-09-18 に CNN画面と突き合わせた実測値を再現すること。"""
    assert rounding.display_int(value) == shown


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

NOW_OBS = dt.datetime(2026, 9, 18, 13, 20, tzinfo=JST)


def test_record_writes_one_row_per_field(tmp_path):
    path = tmp_path / "rounding_probe.csv"
    record(path, [
        make_row("2026-09-18", "current", 28.5428571428571, False, NOW_OBS),
        make_row("2026-09-18", "prev_close", 28.69, True, NOW_OBS),
    ])
    rows = _read(path)
    assert list(rows[0]) == HEADER
    assert [r["field"] for r in rows] == ["current", "prev_close"]
    assert rows[0]["comparable"] == "no"   # 現在値は判定に使わない
    assert rows[1]["comparable"] == "yes"
    assert rows[1]["floor"] == "28" and rows[1]["round_half_up"] == "29"


def test_record_dedupes_by_date_and_field(tmp_path):
    path = tmp_path / "rounding_probe.csv"
    record(path, [make_row("2026-09-18", "w1", 32.69, True, NOW_OBS)])
    record(path, [make_row("2026-09-18", "w1", 32.70, True, NOW_OBS)])
    rows = _read(path)
    assert len(rows) == 1 and rows[0]["value"] == "32.7"


def test_record_preserves_hand_entered_site_display(tmp_path):
    path = tmp_path / "rounding_probe.csv"
    record(path, [make_row("2026-09-18", "m1", 54.63, True, NOW_OBS)])
    rows = _read(path)
    rows[0]["site_display"] = "54"
    _write(path, rows)
    record(path, [make_row("2026-09-18", "m1", 54.63, True, NOW_OBS)])
    assert _read(path)[0]["site_display"] == "54"


def test_rows_from_payload_covers_all_five_fields():
    from synthetic import business_days, make_payload
    from fg.rounding_probe import rows_from_payload
    days = business_days(dt.date(2026, 9, 18), 3)
    rows = rows_from_payload(make_payload(days, [30.0, 29.0, 28.54]), NOW_OBS)
    assert [r["field"] for r in rows] == ["current", "prev_close", "w1", "m1", "y1"]
    assert [r["comparable"] for r in rows] == ["no", "yes", "yes", "yes", "yes"]


# --- 判定 ----------------------------------------------------------------

def rows_for(entries, comparable=True):
    """entries: (field, 値, 画面表示 or None) のリスト。すべて同じ as_of として扱う。"""
    out = []
    for field, value, shown in entries:
        row = make_row("2026-09-18", field, value, comparable, NOW)
        row["site_display"] = "" if shown is None else str(shown)
        out.append(row)
    return out


def test_decide_needs_three_discriminating_observations():
    verdict = decide(rows_for([("prev_close", 29.63, 29), ("w1", 30.71, 30)]))
    assert verdict["decided"] is False
    assert verdict["discriminating"] == 2
    assert verdict["survivors"] == ["floor"]


def test_decide_reproduces_the_p1_determination():
    """2026-09-18 に実際に使った4値。これで floor と確定した。"""
    verdict = decide(rows_for([
        ("prev_close", 28.69, 28),
        ("w1", 32.69, 32),
        ("m1", 54.63, 54),
        ("y1", 66.54, 66),
    ]))
    assert verdict["decided"] is True
    assert verdict["rule"] == "floor"
    assert verdict["discriminating"] == 4


def test_decide_confirms_round_half_up():
    verdict = decide(rows_for([
        ("prev_close", 29.63, 30),
        ("w1", 30.71, 31),
        ("m1", 44.52, 45),
    ]))
    assert verdict["decided"] is True
    assert verdict["rule"] == "round_half_up"


def test_decide_reports_conflict_when_neither_rule_explains_it():
    verdict = decide(rows_for([
        ("prev_close", 29.63, 29),
        ("w1", 30.71, 31),  # 矛盾
        ("m1", 44.52, 44),
    ]))
    assert verdict["decided"] is False
    assert verdict["survivors"] == []


def test_decide_skips_the_current_value_even_when_filled():
    """現在値は時点がずれるので、埋まっていても判定に使わない。"""
    verdict = decide(
        rows_for([("prev_close", 28.69, 28), ("w1", 32.69, 32), ("m1", 54.63, 54)])
        + rows_for([("current", 28.5428571428571, 29)], comparable=False)
    )
    assert verdict["skipped_not_comparable"] == 1
    assert verdict["decided"] is True
    assert verdict["rule"] == "floor"


def test_decide_ignores_rows_without_site_display():
    verdict = decide(rows_for([("prev_close", 29.63, None)]))
    assert verdict["observations"] == 0
    assert verdict["decided"] is False
