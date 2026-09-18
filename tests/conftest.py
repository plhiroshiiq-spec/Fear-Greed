"""pytest フィクスチャ。合成ペイロードの組み立ては synthetic.py にある。"""

from __future__ import annotations

import pytest

from synthetic import business_days, make_payload


@pytest.fixture
def payload_factory():
    return make_payload


@pytest.fixture
def bdays():
    return business_days
