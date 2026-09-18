# fg-engine

CNN Fear & Greed Index を取得して `data/fg.json`(schema 2)を吐く。
仕様は [SPEC.md](SPEC.md)(FG ENGINE 仕様書 v2.2)。**米国版のみ・運用費0円**。

現在のフェーズ: **P5(EXT / 通知)まで実装済み**。P1 は検収済み。

- `fg/` … CNN取得エンジン(Python)。P1
- `sumideck/` … SUMI DECK 側の実装(Kotlin)。P2。→ [sumideck/README.md](sumideck/README.md)

## 構成

```
fg/cnn.py            取得・リトライ・日付付きURLへのフォールバック・妥当性検証 (1章/4章)
fg/build_json.py     fg.json と history_us.csv の組み立て、失敗時の stale 処理 (2章/4章)
fg/rounding.py       表示用の丸め規則。唯一の定義箇所 (1.3章)
fg/rounding_probe.py 丸め規則の照合ツール (3章 P1)
tools/check_no_raw_data.py  生データ混入の検査 (8章)
tests/               pytest。入力は合成データのみ
```

## 出力

| ファイル | 内容 |
|---|---|
| `data/fg.json` | schema 2。SUMI DECK が読む唯一のファイル |
| `data/history_us.csv` | `date, score, 各要素score` の追記保存 |
| `data/failure_state.json` | 連続失敗カウンタ(engine 内部用) |
| `data/rounding_probe.csv` | 丸め規則の照合用の観測 |
| `data/ext.json` | EXT(SOX指数の前日比%)。SPEC.md 9.2章 |

**CNN の生データは保存しない**(SPEC.md 8章)。置くのはスコアと派生統計だけで、
`tools/check_no_raw_data.py` が CI で機械的に確認する。

## 実行

```sh
pip install -r requirements-dev.txt
python -m pytest -q          # テスト
python -m fg.build_json -v   # data/fg.json を更新
python -m fg.ext -v          # data/ext.json を更新(任意)
```

## スケジュール (SPEC.md 4章)

| ジョブ | JST | cron (UTC) |
|---|---|---|
| morning | 火〜土 06:40 | `40 21 * * 1-5` |
| intraday | 火〜土 00:30 | `30 15 * * 1-5` |

失敗時: 指数バックオフで3回 → 日付付きURLにフォールバック → なお失敗なら
既存の `fg.json` を残したまま `stale:true` だけ立てて終了コード0。
3回連続で失敗したときだけワークフローを失敗させる(GitHubからメールが飛ぶ)。

## 確定事項と残課題

- 丸め規則は **切り捨て(floor)** で確定(2026-09-18)。→ [docs/rounding.md](docs/rounding.md)
- `history` の日次値と `previous_close` のずれ(`streak` に影響)→ [docs/spec_diff.md](docs/spec_diff.md) D8 / D10
- EXT の取得失敗時は **枠を残して値を出さない**(2026-09-18 決定)→ [docs/spec_diff.md](docs/spec_diff.md) D16
- 終値ソースの選定 → [docs/price_source.md](docs/price_source.md) / EXT → [docs/ext_source.md](docs/ext_source.md)
- 仕様との差分 → [docs/spec_diff.md](docs/spec_diff.md)
