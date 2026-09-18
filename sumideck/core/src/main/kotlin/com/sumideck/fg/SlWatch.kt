package com.sumideck.fg

/**
 * SL WATCH。SPEC.md 9.1章。
 *
 * 保有建玉のうち、直近終値がSLラインを割っている銘柄数を出す。**J-Quantsを使わない。**
 *
 * - 判定: `close < sl` を「割れ」
 * - `dist_pct = (close − sl) / close × 100` の最小値も出す
 * - 表示: `SL 建玉5 / 割れ1 (7203) 最短 +1.2% 09/17終値`
 * - **判定に使った終値の日付を必ず添える**
 *
 * 終値が入っていない建玉は [SlWatchState.unpriced] として別に数える。
 * 割れ0件と「まだ入力していない」を混同させないため(設計原則1)。
 */
data class Breach(
    val code: String,
    val close: Double,
    val sl: Double,
    val distPct: Double,
)

data class SlWatchState(
    /** 建玉の総数。 */
    val positions: Int,
    /** 終値が入っていて判定できた建玉の数。 */
    val evaluated: Int,
    /** 終値がまだ入っていない建玉の数。 */
    val unpriced: Int,
    val breaches: List<Breach>,
    /** SLまでの最短距離(%)。判定できた建玉が無ければ null。 */
    val minDistancePct: Double?,
    val minDistanceCode: String?,
    /** 判定に使った終値の日付。建玉ごとに違う場合は**最も古い日付**(新しく見せない)。 */
    val asOfDate: String?,
    /** 使った終値の日付が揃っていないか。 */
    val mixedDates: Boolean,
) {
    val breachCount: Int get() = breaches.size

    /** 6.2章の展開条件に渡す値。判定できていなければ null。 */
    val breachesOrNull: Int? get() = if (evaluated == 0) null else breachCount

    /**
     * `SL 建玉5 / 割れ1 (7203) 最短 +1.2% 09/17終値`
     *
     * 終値が1件も入っていない日は数値を作らず、何が足りないかを出す(6.4章 / 設計原則1)。
     */
    fun render(): String {
        if (positions == 0) return "SL 建玉0"
        if (evaluated == 0) return "SL 建玉$positions / 終値未入力"

        val parts = mutableListOf("SL 建玉$positions")
        parts += "割れ$breachCount" + (breaches.firstOrNull()?.let { " (${it.code})" } ?: "")
        minDistancePct?.let { parts += "最短 ${formatPct(it)}" }
        asOfDate?.let { parts += "${shortDate(it)}終値" + if (mixedDates) "(最古)" else "" }
        if (unpriced > 0) parts += "未入力$unpriced"
        return parts.joinToString(" / ", limit = 10)
    }

    companion object {
        /** `+1.2%` / `−3.4%` / `±0%`。android モジュールからも使うので public。 */
        fun formatPct(value: Double): String {
            val rounded = kotlin.math.round(value * 10.0) / 10.0
            val sign = if (rounded > 0) "+" else if (rounded < 0) "−" else "±"
            return "$sign${kotlin.math.abs(rounded)}%"
        }

        /** `2026-09-17` → `09/17`。読めなければ元のまま。 */
        fun shortDate(iso: String): String =
            PanelBuilder.shortDate(iso) ?: iso
    }
}

object SlWatch {

    /** `dist_pct = (close − sl) / close × 100`(9.1章)。 */
    fun distancePct(close: Double, sl: Double): Double =
        if (close == 0.0) 0.0 else (close - sl) / close * 100.0

    fun evaluate(positions: List<Position>, closes: List<DailyClose>): SlWatchState {
        val byCode = closes.groupBy { it.code }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.date }!! }

        val breaches = mutableListOf<Breach>()
        var minDist: Double? = null
        var minCode: String? = null
        val usedDates = mutableSetOf<String>()
        var evaluated = 0

        positions.forEach { p ->
            val close = byCode[p.code] ?: return@forEach
            evaluated++
            usedDates += close.date
            val dist = distancePct(close.close, p.sl)
            if (close.close < p.sl) {
                breaches += Breach(p.code, close.close, p.sl, dist)
            }
            if (minDist == null || dist < minDist!!) {
                minDist = dist
                minCode = p.code
            }
        }

        return SlWatchState(
            positions = positions.size,
            evaluated = evaluated,
            unpriced = positions.size - evaluated,
            // 割れが深い順に出す(最初の1件を表示に使うため)。
            breaches = breaches.sortedBy { it.distPct },
            minDistancePct = minDist,
            minDistanceCode = minCode,
            asOfDate = usedDates.minOrNull(),
            mixedDates = usedDates.size > 1,
        )
    }
}
