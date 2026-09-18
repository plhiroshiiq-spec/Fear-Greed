package com.sumideck.fg

/**
 * 建玉。SPEC.md 9.1章。
 *
 * 保存先は**端末内 DataStore のみ**。`positions.json` をリポジトリに置かない(9.1章)。
 *
 * @param code 銘柄コード(例 "7203")
 * @param entry 建値
 * @param sl 損切りライン
 * @param shares 株数
 * @param openedAt 建玉を持った日(yyyy-MM-dd)
 * @param slRule SLの自動更新規則。**既定は NONE**(9.1章)
 */
data class Position(
    val code: String,
    val entry: Double,
    val sl: Double,
    val shares: Int,
    val openedAt: String,
    val slRule: SlRule = SlRule.NONE,
    val name: String? = null,
)

/**
 * SLの自動更新規則。SPEC.md 9.1章。
 *
 * > **SLを自動で下げることは絶対にしない。**
 *
 * どの規則を選んでも、新しいSLは現在のSL以上にしかならない([SlRuleEngine] の不変条件)。
 */
enum class SlRule { NONE, BREAKEVEN, ATR, SWING }

/** その日の終値。手入力でも株価ソースでも同じ形で渡す(docs/price_source.md)。 */
data class DailyClose(val code: String, val date: String, val close: Double)

/** 終値の供給元。(a) 手入力 / (b) 株価ソース の差し替え点。 */
fun interface ClosePriceSource {
    /** 直近の終値。まだ入っていない銘柄は結果に含めない(推測で埋めない)。 */
    fun latestCloses(codes: List<String>): List<DailyClose>
}
