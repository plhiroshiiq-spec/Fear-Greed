package com.sumideck.fg

/** スイング点。`index` は描画に渡した系列上の位置。 */
data class SwingPoint(val index: Int, val value: Double, val kind: Kind) {
    enum class Kind { LOW, HIGH }
}

/**
 * スイング点の検出。SPEC.md 5.3.1章。
 *
 * - **スイング安値**: 前後1本より値が小さい(以下)点。**スイング高値**: 前後1本より大きい(以上)点。
 * - 左右1本で判定する。3本にすると直近の小さな山を拾えず、線が古い高値に引かれる(付録C)。
 * - 当日と前日は**確定していない**ため採用しない(右側1本が未確定のため)。
 */
object Swing {
    /** 末尾から何本を未確定として除外するか。当日と前日の2本。 */
    const val UNCONFIRMED_TAIL = 2

    fun detect(series: List<Double>): List<SwingPoint> {
        if (series.size < 3) return emptyList()
        // 右端の未確定ぶんを除いた範囲だけを候補にする。
        val lastConfirmed = series.size - 1 - UNCONFIRMED_TAIL
        val out = mutableListOf<SwingPoint>()
        for (i in 1..lastConfirmed) {
            val prev = series[i - 1]
            val cur = series[i]
            val next = series[i + 1]
            // 「以下」「以上」なので、横ばいを含む点も拾う。高値と安値の両方になる点(平坦部)は
            // どちらとしても成立するため、両方を返す。
            if (cur <= prev && cur <= next) out += SwingPoint(i, cur, SwingPoint.Kind.LOW)
            if (cur >= prev && cur >= next) out += SwingPoint(i, cur, SwingPoint.Kind.HIGH)
        }
        return out
    }

    fun lows(series: List<Double>): List<SwingPoint> =
        detect(series).filter { it.kind == SwingPoint.Kind.LOW }

    fun highs(series: List<Double>): List<SwingPoint> =
        detect(series).filter { it.kind == SwingPoint.Kind.HIGH }
}
