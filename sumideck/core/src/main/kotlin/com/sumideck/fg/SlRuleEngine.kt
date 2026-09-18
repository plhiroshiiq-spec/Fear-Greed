package com.sumideck.fg

import kotlin.math.abs
import kotlin.math.max

/**
 * SLの自動更新。SPEC.md 9.1章。
 *
 * > `sl_rule` があれば再計算(`none` / `breakeven` / `atr` / `swing`)。既定は `none`。
 * > **SLを自動で下げることは絶対にしない。**
 *
 * 最後の一文がこの層の不変条件であり、すべての規則の出口で `max(現在のSL, 候補)` を通す。
 * 規則の中身をどう変えても、SLが下がることはない。
 *
 * ## 規則の定義(SPEC.md に式が無いため、ここで決めた内容)
 *
 * | 規則 | 候補SL | 適用条件 |
 * |---|---|---|
 * | `NONE` | — | 何もしない(既定) |
 * | `BREAKEVEN` | 建値 | 終値が建値を上回っている日だけ |
 * | `ATR` | 終値 − [atrMultiplier] × ATR([atrPeriod]) | 期間ぶんの四本値が揃っている日だけ |
 * | `SWING` | 直近の確定スイング安値 | スイング点が1つ以上ある日だけ |
 *
 * `SWING` は 5.3.1章のスイング判定([Swing])をそのまま使う。当日と前日を確定扱いしない点も
 * 同じなので、翌日に位置が変わる(リペイントする)ことがない。
 *
 * この表は `docs/spec_diff.md` D13 に記録した。式が仕様に無いため、後から変えてよい。
 */
data class SlUpdate(
    val code: String,
    val previousSl: Double,
    val newSl: Double,
    val reason: String,
) {
    val changed: Boolean get() = newSl != previousSl
}

/** ATR の算出に必要な四本値。手入力運用では揃わないことが多い。 */
data class Ohlc(val date: String, val high: Double, val low: Double, val close: Double)

object SlRuleEngine {

    const val DEFAULT_ATR_PERIOD = 14
    const val DEFAULT_ATR_MULTIPLIER = 2.0

    /**
     * 1建玉ぶんのSLを再計算する。**戻り値の `newSl` は必ず `position.sl` 以上**。
     *
     * @param closes その銘柄の終値(古い順)。SWING で使う。
     * @param ohlc その銘柄の四本値(古い順)。ATR で使う。無ければ ATR は何もしない。
     */
    fun recalculate(
        position: Position,
        closes: List<Double> = emptyList(),
        ohlc: List<Ohlc> = emptyList(),
        atrPeriod: Int = DEFAULT_ATR_PERIOD,
        atrMultiplier: Double = DEFAULT_ATR_MULTIPLIER,
    ): SlUpdate {
        val candidate: Pair<Double, String>? = when (position.slRule) {
            SlRule.NONE -> null
            SlRule.BREAKEVEN -> breakeven(position, closes)
            SlRule.ATR -> atr(ohlc, atrPeriod, atrMultiplier)
            SlRule.SWING -> swing(closes)
        }

        if (candidate == null) {
            return SlUpdate(position.code, position.sl, position.sl, "変更なし")
        }

        // ここが不変条件。**絶対に下げない。**
        val raised = max(position.sl, candidate.first)
        val reason = if (raised > position.sl) candidate.second else "候補が現在のSL以下のため据え置き"
        return SlUpdate(position.code, position.sl, raised, reason)
    }

    fun recalculateAll(
        positions: List<Position>,
        closesByCode: Map<String, List<Double>> = emptyMap(),
        ohlcByCode: Map<String, List<Ohlc>> = emptyMap(),
    ): List<SlUpdate> = positions.map {
        recalculate(it, closesByCode[it.code].orEmpty(), ohlcByCode[it.code].orEmpty())
    }

    // --- 各規則 ---

    /** 含み益が出ている日だけ建値まで引き上げる。 */
    internal fun breakeven(position: Position, closes: List<Double>): Pair<Double, String>? {
        val close = closes.lastOrNull() ?: return null
        if (close <= position.entry) return null
        return position.entry to "建値(${position.entry})まで引き上げ"
    }

    /** 終値 − 倍率 × ATR。期間ぶんのデータが無い日は何もしない。 */
    internal fun atr(ohlc: List<Ohlc>, period: Int, multiplier: Double): Pair<Double, String>? {
        val value = atrValue(ohlc, period) ?: return null
        val close = ohlc.last().close
        return (close - multiplier * value) to "ATR($period)×$multiplier で引き上げ"
    }

    /** 直近の確定スイング安値。スイング点が無い期間は何もしない(5.3.2章と同じ考え方)。 */
    internal fun swing(closes: List<Double>): Pair<Double, String>? {
        val low = Swing.lows(closes).lastOrNull() ?: return null
        return low.value to "直近の確定スイング安値(${low.value})まで引き上げ"
    }

    /** Wilder の ATR。期間ぶんの True Range が取れなければ null。 */
    internal fun atrValue(ohlc: List<Ohlc>, period: Int): Double? {
        if (period < 1 || ohlc.size < period + 1) return null
        val trs = (1 until ohlc.size).map { i ->
            val cur = ohlc[i]
            val prevClose = ohlc[i - 1].close
            maxOf(cur.high - cur.low, abs(cur.high - prevClose), abs(cur.low - prevClose))
        }
        if (trs.size < period) return null
        var value = trs.take(period).average()
        for (i in period until trs.size) {
            value = (value * (period - 1) + trs[i]) / period
        }
        return value
    }
}
