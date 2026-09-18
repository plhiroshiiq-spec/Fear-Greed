package com.sumideck.fg

import kotlin.math.abs

/** 補助線の表示設定。SPEC.md 5.3章。既定は水平線のみ。 */
enum class LineMode { HORIZONTAL_ONLY, HORIZONTAL_AND_DIAGONAL, NONE }

/** 水平線(支持 / 抵抗)。SPEC.md 5.3.2章。 */
data class HorizontalLine(
    val kind: Kind,
    /** 線の高さ。直近の確定スイング点の値。 */
    val value: Double,
    /** 起点。ここより左は薄く、ここから右端までは破線で引く。 */
    val originIndex: Int,
) {
    enum class Kind { SUPPORT, RESISTANCE }
}

/** 斜線(下降 / 上昇)。SPEC.md 5.3.3章。最小二乗で当てた直線。 */
data class TrendLine(
    val kind: Kind,
    /** value = slope * index + intercept */
    val slope: Double,
    val intercept: Double,
    /** 当てはめに使った3点のインデックス(古い順)。 */
    val pointIndices: List<Int>,
) {
    enum class Kind { FALLING, RISING }

    fun valueAt(index: Int): Double = slope * index + intercept
}

/** 斜線を抜けた点に打つマーカー。SPEC.md 5.3.3章。3営業日残す。 */
data class BreakMarker(val index: Int, val value: Double, val kind: TrendLine.Kind)

/** 推移線に重ねる補助線ひとそろい。 */
data class Overlay(
    val support: HorizontalLine? = null,
    val resistance: HorizontalLine? = null,
    val falling: TrendLine? = null,
    val rising: TrendLine? = null,
    val markers: List<BreakMarker> = emptyList(),
) {
    /** Y軸スケーリングに含めるべき値(SPEC.md 5.1章「描画中の補助線を含む」)。 */
    fun scaleValues(seriesSize: Int): List<Double> {
        val out = mutableListOf<Double>()
        support?.let { out += it.value }
        resistance?.let { out += it.value }
        for (line in listOfNotNull(falling, rising)) {
            out += line.valueAt(line.pointIndices.first())
            out += line.valueAt(seriesSize - 1)
        }
        return out
    }

    val isEmpty: Boolean
        get() = support == null && resistance == null && falling == null && rising == null
}

object Levels {
    /** 5.3.3章: 傾きの絶対値が1日あたりこの値を超えたら描かない。 */
    const val MAX_ABS_SLOPE_PER_DAY = 1.5

    /** 5.3.3章: 3点目からこの営業日数を超えて経過していたら描かない。 */
    const val MAX_AGE_FROM_LAST_POINT = 10

    /** 5.3.3章: 抜けたマーカーを残す営業日数。 */
    const val MARKER_LIFETIME = 3

    fun build(series: List<Double>, mode: LineMode): Overlay {
        if (mode == LineMode.NONE || series.size < 3) return Overlay()

        val swings = Swing.detect(series)
        val support = swings.lastOrNull { it.kind == SwingPoint.Kind.LOW }
            ?.let { HorizontalLine(HorizontalLine.Kind.SUPPORT, it.value, it.index) }
        val resistance = swings.lastOrNull { it.kind == SwingPoint.Kind.HIGH }
            ?.let { HorizontalLine(HorizontalLine.Kind.RESISTANCE, it.value, it.index) }

        if (mode == LineMode.HORIZONTAL_ONLY) {
            return Overlay(support = support, resistance = resistance)
        }

        var falling = fit(swings.filter { it.kind == SwingPoint.Kind.HIGH }, TrendLine.Kind.FALLING, series.size)
        var rising = fit(swings.filter { it.kind == SwingPoint.Kind.LOW }, TrendLine.Kind.RISING, series.size)

        // 5.3.3章 無効化条件3: 2本が表示区間内で交差したら**両方**消す。
        if (falling != null && rising != null && crossesWithin(falling, rising, series.size)) {
            falling = null
            rising = null
        }

        val markers = buildList {
            falling?.let { addAll(markersFor(series, it)) }
            rising?.let { addAll(markersFor(series, it)) }
        }
        return Overlay(support, resistance, falling, rising, markers)
    }

    /**
     * 直近3つの確定スイング点に最小二乗で直線を当てる。
     * 3点が揃わない日、および5.3.3章の無効化条件に当たる日は null。
     */
    internal fun fit(points: List<SwingPoint>, kind: TrendLine.Kind, seriesSize: Int): TrendLine? {
        if (points.size < 3) return null
        val last3 = points.takeLast(3)

        val n = last3.size
        val sumX = last3.sumOf { it.index.toDouble() }
        val sumY = last3.sumOf { it.value }
        val sumXY = last3.sumOf { it.index.toDouble() * it.value }
        val sumXX = last3.sumOf { it.index.toDouble() * it.index.toDouble() }
        val denom = n * sumXX - sumX * sumX
        if (denom == 0.0) return null   // 3点が同じインデックスに重なることは無いが、念のため

        val slope = (n * sumXY - sumX * sumY) / denom
        val intercept = (sumY - slope * sumX) / n

        // 無効化条件1: 傾きが立ちすぎ(急落局面で数日で0や100を割るため)
        if (abs(slope) > MAX_ABS_SLOPE_PER_DAY) return null
        // 無効化条件2: 3点目から10営業日を超えて経過(古い線を延々と延ばさない)
        if ((seriesSize - 1) - last3.last().index > MAX_AGE_FROM_LAST_POINT) return null

        return TrendLine(kind, slope, intercept, last3.map { it.index })
    }

    /** 2本が表示区間(最初の点〜右端)の内側で交差するか。 */
    internal fun crossesWithin(a: TrendLine, b: TrendLine, seriesSize: Int): Boolean {
        val from = minOf(a.pointIndices.first(), b.pointIndices.first())
        val to = seriesSize - 1
        if (to <= from) return false
        val dFrom = a.valueAt(from) - b.valueAt(from)
        val dTo = a.valueAt(to) - b.valueAt(to)
        if (dFrom == 0.0 || dTo == 0.0) return true
        return (dFrom > 0.0) != (dTo > 0.0)
    }

    /**
     * 5.3.3章: 終値が線を上抜けた(下降線)/ 下抜けた(上昇線)日にマーカーを打つ。
     * マーカーは3営業日残すので、右端から3本ぶんの抜けだけを返す。
     */
    internal fun markersFor(series: List<Double>, line: TrendLine): List<BreakMarker> {
        val start = line.pointIndices.first()
        val oldest = maxOf(start + 1, series.size - MARKER_LIFETIME)
        val out = mutableListOf<BreakMarker>()
        for (i in oldest until series.size) {
            val prevAbove = series[i - 1] > line.valueAt(i - 1)
            val nowAbove = series[i] > line.valueAt(i)
            val broke = when (line.kind) {
                TrendLine.Kind.FALLING -> !prevAbove && nowAbove   // 上抜け
                TrendLine.Kind.RISING -> prevAbove && !nowAbove    // 下抜け
            }
            if (broke) out += BreakMarker(i, series[i], line.kind)
        }
        return out
    }
}
