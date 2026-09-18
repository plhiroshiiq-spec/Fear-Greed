package com.sumideck.fg

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelsTest {

    private val zigzag = listOf(30.0, 28.0, 32.0, 27.0, 33.0, 29.0, 31.0)

    // --- 水平線 (5.3.2) ---

    @Test fun `支持線と抵抗線は直近の確定スイング点`() {
        val o = Levels.build(zigzag, LineMode.HORIZONTAL_ONLY)
        assertEquals(27.0, o.support?.value)
        assertEquals(3, o.support?.originIndex)
        assertEquals(33.0, o.resistance?.value)
        assertEquals(4, o.resistance?.originIndex)
    }

    @Test fun `スイング点が無い期間は線を引かない`() {
        // 5.3.2章: 直近値や期間の最大最小で代用しない。
        val monotone = (0 until 30).map { 20.0 + it * 0.5 }
        val o = Levels.build(monotone, LineMode.HORIZONTAL_ONLY)
        assertNull(o.support)
        assertNull(o.resistance)
        assertTrue(o.isEmpty)
    }

    @Test fun `線なし設定では何も引かない`() {
        assertTrue(Levels.build(zigzag, LineMode.NONE).isEmpty)
    }

    @Test fun `既定の水平線のみでは斜線を引かない`() {
        val o = Levels.build(zigzag, LineMode.HORIZONTAL_ONLY)
        assertNull(o.falling)
        assertNull(o.rising)
    }

    // --- 斜線 (5.3.3) ---

    /**
     * 指定した折れ点の間を直線で埋めた系列を作る。
     *
     * 値が横ばいだと 5.3.1章の「以下 / 以上」判定でその区間が丸ごとスイング点になるため、
     * 折れ点以外は必ず単調にする。実データ(float)ではぴったり同値になる日がほぼ無いので、
     * この作り方のほうが実物に近い。
     */
    private fun pivots(size: Int, vararg points: Pair<Int, Double>): List<Double> {
        val out = MutableList(size) { 0.0 }
        val sorted = points.sortedBy { it.first }
        for ((a, b) in sorted.zipWithNext()) {
            val span = b.first - a.first
            for (i in a.first..b.first) {
                out[i] = a.second + (b.second - a.second) * (i - a.first) / span
            }
        }
        return out
    }

    /** 高値が index 2,6,10 で 40,38,36、安値が 4,8,12 で 30,29,28 になるゆるやかな系列。 */
    private fun gentle(): List<Double> = pivots(
        16,
        0 to 36.0, 2 to 40.0, 4 to 30.0, 6 to 38.0, 8 to 29.0, 10 to 36.0, 12 to 28.0, 15 to 31.0,
    )

    @Test fun `3点そろえば最小二乗で線を当てる`() {
        val highs = Swing.highs(gentle())
        val line = Levels.fit(highs, TrendLine.Kind.FALLING, gentle().size)
        assertNotNull(line)
        assertEquals(listOf(2, 6, 10), line.pointIndices)
        assertTrue(line.slope < 0.0)
        assertEquals(40.0, line.valueAt(2), 0.001)   // 等間隔3点なので残差0
    }

    @Test fun `3点が揃わない日は線を描かない`() {
        val twoHighs = listOf(
            SwingPoint(2, 40.0, SwingPoint.Kind.HIGH),
            SwingPoint(6, 38.0, SwingPoint.Kind.HIGH),
        )
        assertNull(Levels.fit(twoHighs, TrendLine.Kind.FALLING, 16))
    }

    @Test fun `無効化条件1 傾きが1日1_5ポイントを超えたら描かない`() {
        val steep = listOf(
            SwingPoint(0, 60.0, SwingPoint.Kind.HIGH),
            SwingPoint(1, 58.0, SwingPoint.Kind.HIGH),
            SwingPoint(2, 56.0, SwingPoint.Kind.HIGH),   // -2.0/日
        )
        assertNull(Levels.fit(steep, TrendLine.Kind.FALLING, 5))
        val ok = listOf(
            SwingPoint(0, 60.0, SwingPoint.Kind.HIGH),
            SwingPoint(1, 59.0, SwingPoint.Kind.HIGH),
            SwingPoint(2, 58.0, SwingPoint.Kind.HIGH),   // -1.0/日
        )
        assertNotNull(Levels.fit(ok, TrendLine.Kind.FALLING, 5))
    }

    @Test fun `無効化条件2 3点目から10営業日を超えたら描かない`() {
        val pts = listOf(
            SwingPoint(0, 40.0, SwingPoint.Kind.HIGH),
            SwingPoint(2, 39.0, SwingPoint.Kind.HIGH),
            SwingPoint(4, 38.0, SwingPoint.Kind.HIGH),
        )
        assertNotNull(Levels.fit(pts, TrendLine.Kind.FALLING, 15))  // 4 → 14 は10営業日
        assertNull(Levels.fit(pts, TrendLine.Kind.FALLING, 16))     // 11営業日で無効
    }

    @Test fun `無効化条件3 2本が表示区間内で交差したら両方消す`() {
        // 高値が速く下がり、安値がゆるやかに下がる → 付録Cで実際に起きた形。
        val falling = TrendLine(TrendLine.Kind.FALLING, -1.4, 50.0, listOf(0, 4, 8))
        val rising = TrendLine(TrendLine.Kind.RISING, -0.2, 30.0, listOf(0, 4, 8))
        assertTrue(Levels.crossesWithin(falling, rising, 20))

        val parallel = TrendLine(TrendLine.Kind.RISING, -1.4, 30.0, listOf(0, 4, 8))
        assertTrue(!Levels.crossesWithin(falling, parallel, 20))
    }

    @Test fun `交差したら支持と抵抗の水平線は残して斜線だけ消す`() {
        val series = crossingSeries()
        val o = Levels.build(series, LineMode.HORIZONTAL_AND_DIAGONAL)
        assertNull(o.falling)
        assertNull(o.rising)
        assertNotNull(o.support)
        assertNotNull(o.resistance)
    }

    /**
     * 高値の下降(-1.25/日)が安値の下降(-0.25/日)より速く、表示区間内で交差する系列。
     * 付録Cで実際に起きた形(高値 -2.46/日 対 安値 -1.78/日)を、傾き上限に収まる範囲で再現する。
     */
    private fun crossingSeries(): List<Double> = pivots(
        16,
        0 to 38.0, 2 to 44.0, 4 to 33.0, 6 to 39.0, 8 to 32.0, 10 to 34.0, 12 to 31.0, 15 to 33.0,
    )

    @Test fun `フィクスチャが意図どおりのスイング点を持つ`() {
        assertEquals(listOf(2, 6, 10), Swing.highs(gentle()).map { it.index })
        assertEquals(listOf(4, 8, 12), Swing.lows(gentle()).map { it.index })
        assertEquals(listOf(2, 6, 10), Swing.highs(crossingSeries()).map { it.index })
        assertEquals(listOf(4, 8, 12), Swing.lows(crossingSeries()).map { it.index })
    }

    @Test fun `横ばいが続く区間はスイング点だらけになる`() {
        // 5.3.1章の「以下 / 以上」をそのまま実装した結果。実データでは同値がほぼ出ない
        // (CNN実データ250ペア中2ペア)ので実害は無いが、挙動として固定しておく。
        val flat = List(10) { 30.0 }
        assertEquals(7, Swing.highs(flat).size)
        assertEquals(7, Swing.lows(flat).size)
    }

    // --- 抜けマーカー (5.3.3) ---

    @Test fun `下降線を上抜けた点にマーカーを打つ`() {
        val line = TrendLine(TrendLine.Kind.FALLING, -1.0, 40.0, listOf(0, 2, 4))
        //  index:  0     1     2     3     4     5     6
        //  line : 40    39    38    37    36    35    34
        val series = listOf(40.0, 38.0, 37.0, 36.0, 35.0, 34.0, 40.0)
        val markers = Levels.markersFor(series, line)
        assertEquals(listOf(6), markers.map { it.index })
        assertEquals(TrendLine.Kind.FALLING, markers.single().kind)
    }

    @Test fun `マーカーは3営業日しか残さない`() {
        val line = TrendLine(TrendLine.Kind.FALLING, -1.0, 40.0, listOf(0, 2, 4))
        // 古い上抜け(index 2)は範囲外になる。
        val series = listOf(40.0, 30.0, 45.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0, 30.0)
        assertTrue(Levels.markersFor(series, line).none { it.index == 2 })
    }

    @Test fun `Y軸スケーリングに補助線の値が含まれる`() {
        val series = listOf(30.0, 31.0, 30.5)
        val overlay = Overlay(
            support = HorizontalLine(HorizontalLine.Kind.SUPPORT, 20.0, 0),
            resistance = HorizontalLine(HorizontalLine.Kind.RESISTANCE, 45.0, 1),
        )
        val scale = ChartScale.of(series, overlay.scaleValues(series.size))
        assertNotNull(scale)
        assertEquals(16.0, scale.min, 0.001)   // 20 − 4
        assertEquals(49.0, scale.max, 0.001)   // 45 + 4
    }

    @Test fun `傾きの上限は絶対値で効く`() {
        val steepUp = listOf(
            SwingPoint(0, 20.0, SwingPoint.Kind.LOW),
            SwingPoint(1, 22.0, SwingPoint.Kind.LOW),
            SwingPoint(2, 24.0, SwingPoint.Kind.LOW),
        )
        assertTrue(abs(2.0) > Levels.MAX_ABS_SLOPE_PER_DAY)
        assertNull(Levels.fit(steepUp, TrendLine.Kind.RISING, 5))
    }
}
