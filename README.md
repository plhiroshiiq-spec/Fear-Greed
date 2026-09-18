# fg-engine

CNN Fear & Greed Index を取得して `data/fg.json`(schema 2)を吐く。
仕様は [SPEC.md](SPEC.md)(FG ENGINE 仕様書 v2.2)。**米国版のみ・運用費0円**。

現在のフェーズ: **P1(CNN取得 → fg.json → GitHub Actions 稼働)**

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

**CNN の生データは保存しない**(SPEC.md 8章)。置くのはスコアと派生統計だけで、
`tools/check_no_raw_data.py` が CI で機械的に確認する。

## 実行

```sh
pip install -r requirements-dev.txt
python -m pytest -q          # テスト
python -m fg.build_json -v   # data/fg.json を更新
```

## スケジュール (SPEC.md 4章)

| ジョブ | JST | cron (UTC) |
|---|---|---|
| morning | 火〜土 06:40 | `40 21 * * 1-5` |
| intraday | 火〜土 00:30 | `30 15 * * 1-5` |

失敗時: 指数バックオフで3回 → 日付付きURLにフォールバック → なお失敗なら
既存の `fg.json` を残したまま `stale:true` だけ立てて終了コード0。
3回連続で失敗したときだけワークフローを失敗させる(GitHubからメールが飛ぶ)。

## 未了

- 丸め規則(四捨五入 / 切り捨て)は**未確定**。暫定は四捨五入。→ [docs/rounding.md](docs/rounding.md)
- 仕様との差分 → [docs/spec_diff.md](docs/spec_diff.md)
