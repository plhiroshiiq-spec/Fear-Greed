package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingTest {

    @Test fun `左右1本で判定する`() {
        //            0     1     2     3     4     5     6
        val s = listOf(30.0, 28.0, 32.0, 27.0, 33.0, 29.0, 31.0)
        // 末尾2本(index 5,6)は未確定なので候補から外れる。
        assertEquals(listOf(1, 3), Swing.lows(s).map { it.index })
        assertEquals(listOf(2, 4), Swing.highs(s).map { it.index })
    }

    @Test fun `当日と前日はスイング点にしない`() {
        // index 5 は前後より小さいが、右側1本(index 6)が未確定なので採用しない。
        val s = listOf(30.0, 31.0, 32.0, 33.0, 34.0, 20.0, 35.0)
        assertTrue(Swing.lows(s).none { it.index >= 5 })
    }

    @Test fun `3本未満では検出しない`() {
        assertEquals(emptyList(), Swing.detect(listOf(30.0, 31.0)))
        assertEquals(emptyList(), Swing.detect(emptyList()))
    }

    @Test fun `単調な系列にはスイング点が無い`() {
        val s = (0 until 20).map { 20.0 + it }
        assertTrue(Swing.lows(s).isEmpty())
        assertTrue(Swing.highs(s).isEmpty())
    }

    @Test fun `以下と以上なので横ばいも拾う`() {
        val s = listOf(30.0, 28.0, 28.0, 28.0, 33.0, 29.0, 31.0)
        // 平坦部は安値としても高値としても成立する。
        assertTrue(Swing.lows(s).map { it.index }.containsAll(listOf(1, 2, 3)))
    }
}
