#!/usr/bin/env python3
"""CNNの生データがリポジトリに混入していないか調べる (SPEC.md 8章)。

data/ 配下に置いてよいのは fg.json / history_us.csv / failure_state.json /
rounding_probe.csv だけ。fg.json にCNNの内部キーが現れたら失敗させる。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"

ALLOWED = {"fg.json", "history_us.csv", "failure_state.json", "rounding_probe.csv", ".gitkeep"}

# 1.2章のCNN内部キー。これらが成果物に現れたら生データを持ち込んでいる。
FORBIDDEN_KEYS = (
    "fear_and_greed_historical",
    "market_momentum_sp500",
    "market_momentum_sp125",
    "stock_price_strength",
    "stock_price_breadth",
    "put_call_options",
    "market_volatility_vix",
    "junk_bond_demand",
    "safe_haven_demand",
)


def main() -> int:
    problems: list[str] = []

    if DATA.exists():
        for path in sorted(DATA.rglob("*")):
            if path.is_dir():
                problems.append(f"{path.relative_to(ROOT)}: data/ にサブディレクトリを作らない")
            elif path.name not in ALLOWED:
                problems.append(f"{path.relative_to(ROOT)}: 想定外のファイル")

    fg_json = DATA / "fg.json"
    if fg_json.exists():
        text = fg_json.read_text(encoding="utf-8")
        for key in FORBIDDEN_KEYS:
            if key in text:
                problems.append(f"data/fg.json にCNNの内部キー {key!r} が含まれている")
        doc = json.loads(text)
        if set(doc) - {"schema", "generated_at", "us"}:
            problems.append(f"data/fg.json に想定外のトップレベルキー: {sorted(set(doc))}")

    for line in problems:
        print(f"NG: {line}", file=sys.stderr)
    if problems:
        return 1
    print("OK: 生データの混入なし")
    return 0


if __name__ == "__main__":
    sys.exit(main())
