# sumideck-fg — SUMI DECK 側の実装(P2)

SPEC.md 5章「SUMI DECK 側仕様」の実装。採用デザインは**案E「帯と推移」**(5.1章)+
**自動描画ライン**(5.3章)。半円ゲージは作らない。

## ⚠️ この実装の検証状況

| 層 | ディレクトリ | この環境でビルド・テストしたか |
|---|---|---|
| ロジック(純Kotlin) | `core/` | **した。53件のテストが通っている** |
| 描画・取得(Android) | `android/` | **していない**(理由は下記) |

**Android 側をビルドできなかった理由**: 開発環境に Android SDK が無く、外向き通信ポリシーが
Google Maven(`dl.google.com`)を遮断しているため、Compose の依存を1つも解決できない。
そこで**判断を伴う処理をすべて `core/` に寄せ**、`android/` は「`core` が出した値を描く / 読む」
だけにしてある。バグが入りやすい部分は JVM のテストで押さえてある。

`android/` は SUMI DECK のリポジトリに貼り込んでから初めてコンパイルされる。

## 構成

```
core/                                 純Kotlin。Android非依存。Gradleでテストできる
  FgModel.kt          fg.json (schema 2) の読み取り。ignoreUnknownKeys、jpキー不在が前提
  HistoryCellSerializer.kt  ["2026-09-18", 28.54] の型混在を吸収する
  Rating.kt           5区分と日本語表記、位置の帯の定義、表示の丸め(切り捨て)
  ChartScale.kt       推移線のY軸スケーリング(最小値−4 / 最大値+4)
  Swing.kt            スイング点の検出(5.3.1章)
  Levels.kt           水平線(5.3.2章)と斜線(5.3.3章)、無効化条件3つ、抜けマーカー
  PanelState.kt       描画に渡す状態、折りたたみ行(6.3章)、展開条件(6.2章)
  FgLoader.kt         取得失敗時にキャッシュへ落とす判断(機内モードの検収)
  IsoTime.kt          generated_at の解釈(java.time 非依存)

android/                              SUMI DECK に貼り込むソース。ここではビルドしない
  com/sumideck/fg/ui/
    FearGreedPanel.kt   案Eの本体。数値行 / 位置の帯 / 推移線 / 展開時の4段目
    PositionBand.kt     2段目の帯とマーカー(5.1章)
    HistoryChart.kt     3段目の折れ線と補助線(5.1章 / 5.3章)
    OverlayLegend.kt    凡例(5.3.4章)
    ComponentBar.kt     7要素の横棒
    FgTheme.kt          色。**色を使うのは自動描画の線と凡例だけ**(5.3.5章)
    FgDimens.kt         5.1章 / 5.3章の実装寸法をそのまま定数にしたもの
    FgText.kt           Material非依存の最小テキスト
  com/sumideck/fg/data/
    FgRepository.kt     取得とキャッシュの組み立て。判断は core の FgLoader に寄せてある
    HttpFgRemoteSource.kt  raw URL を叩くだけの実装
```

## テストの実行

```sh
gradle -p sumideck test
```

Maven Central がバースト取得に 429 を返すことがあるため `gradle.properties` で
依存解決を直列化している。それでも 429 が出たら少し置いて再実行すること。

## SUMI DECK への取り込み手順

1. `core/` を Android プロジェクトのモジュールとして取り込む(`include(":fg-core")` など)。
   Android 非依存なので `kotlin("jvm")` のままでよい。
2. `android/com/sumideck/fg/ui` と `.../data` のソースをアプリモジュールにコピーする。
3. **既存の流儀に合わせる**(SPEC.md 5章)。衝突したら既存側を優先する。
   - `FgText.kt` → 既存の Text ラッパがあれば差し替える
   - `FgTheme.kt` → 既存テーマに同等のトークンがあればそちらを使う。
     ただし**色を自動描画の線と凡例だけに限る規定(5.3.5章)は崩さないこと**
   - `FgRepository` の DI → 既存の DI に合わせる
4. `FgCache` を DataStore で実装する。
5. 更新契機を繋ぐ。ランチャー復帰時(30分判定は `FgLoader.shouldRefresh`)と、
   WorkManager で 07:00 JST 前後に1回。

## 守らせている規定(取り込み後に壊さないこと)

- **モノクロ**: 区分・強弱を色で表現しない。色の例外は自動描画の線と凡例だけ(5.2章 / 5.3.5章)。
- **折りたたみでも数値は残す**(6.3章)。畳むのは推移線・補助線・目盛り数字。
- **枠を消さない**(6.4章)。取得失敗・stale・欠損でも畳んだ1行と最終取得日を出す。
- **履歴を埋めない**(5.1章)。60本に満たない日はある分だけ描く。
- **スイング点が無い期間は線を引かない**(5.3.2章)。直近値や最大最小で代用しない。
- **丸めは切り捨て**(P1で確定、docs/rounding.md)。`kotlin.math.round` を使わない。
