package com.sumideck.fg

/**
 * 推移線のY軸スケーリング。SPEC.md 5.1章 3段目。
 *
 * > Y軸は固定ではなく、**表示区間(推移線と描画中の補助線を含む)の最小値−4 と
 * > 最大値+4 でスケーリング**する(固定0–100だと平常時に線が寝て動きが読めないため)。
 */
data class ChartScale(val min: Double, val max: Double) {
    val span: Double get() = max - min

    /** 値を 0(下端)..1(上端)に正規化する。 */
    fun normalize(value: Double): Double =
        if (span <= 0.0) 0.5 else ((value - min) / span)

    companion object {
        const val PADDING = 4.0

        /**
         * @param series 推移線の値
         * @param extraValues 描画中の補助線が表示区間内で取る値(水平線の高さ、斜線の両端など)
         * @return 値が1つも無ければ null(描くものが無い)
         */
        fun of(series: List<Double>, extraValues: List<Double> = emptyList()): ChartScale? {
            val all = series + extraValues
            if (all.isEmpty()) return null
            return ChartScale(all.min() - PADDING, all.max() + PADDING)
        }
    }
}
