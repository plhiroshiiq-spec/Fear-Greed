package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlWatchTest {

    private fun pos(code: String, sl: Double, entry: Double = 1000.0, shares: Int = 100) =
        Position(code = code, entry = entry, sl = sl, shares = shares, openedAt = "2026-09-01")

    private fun close(code: String, close: Double, date: String = "2026-09-17") =
        DailyClose(code, date, close)

    // --- 判定 (9.1章) ---

    @Test fun `終値がSLを下回れば割れ`() {
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2800.0), pos("6758", sl = 3000.0)),
            listOf(close("7203", 2750.0), close("6758", 3200.0)),
        )
        assertEquals(1, state.breachCount)
        assertEquals("7203", state.breaches.single().code)
    }

    @Test fun `SLちょうどは割れではない`() {
        // 9.1章の定義は `close < sl`。等号は割れに入れない。
        val state = SlWatch.evaluate(listOf(pos("7203", sl = 2800.0)), listOf(close("7203", 2800.0)))
        assertEquals(0, state.breachCount)
    }

    @Test fun `最短距離の計算`() {
        // dist_pct = (close − sl) / close × 100
        assertEquals(1.2, SlWatch.distancePct(2500.0, 2470.0), 0.05)
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2470.0), pos("6758", sl = 2000.0)),
            listOf(close("7203", 2500.0), close("6758", 3000.0)),
        )
        assertEquals("7203", state.minDistanceCode)
        assertEquals(1.2, state.minDistancePct!!, 0.05)
    }

    @Test fun `割れている銘柄の距離は負になる`() {
        val state = SlWatch.evaluate(listOf(pos("7203", sl = 2800.0)), listOf(close("7203", 2750.0)))
        assertTrue(state.minDistancePct!! < 0.0)
    }

    // --- 表示 (9.1章) ---

    @Test fun `表示にはSPECの形が出る`() {
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2470.0), pos("6758", sl = 3000.0), pos("9984", sl = 8000.0),
                   pos("8035", sl = 20000.0), pos("4063", sl = 3000.0)),
            listOf(close("7203", 2500.0), close("6758", 2900.0), close("9984", 9000.0),
                   close("8035", 25000.0), close("4063", 3500.0)),
        )
        // SL 建玉5 / 割れ1 (6758) / 最短 −3.4% / 09/17終値
        val text = state.render()
        assertTrue(text.startsWith("SL 建玉5"), text)
        assertTrue(text.contains("割れ1 (6758)"), text)
        assertTrue(text.contains("最短"), text)
        assertTrue(text.contains("09/17終値"), text)
    }

    @Test fun `判定に使った終値の日付を必ず添える`() {
        val state = SlWatch.evaluate(listOf(pos("7203", sl = 2000.0)), listOf(close("7203", 2500.0)))
        assertEquals("2026-09-17", state.asOfDate)
        assertTrue(state.render().contains("09/17終値"))
    }

    @Test fun `日付が揃っていなければ最も古い日付を出す`() {
        // 新しく見せない。
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2000.0), pos("6758", sl = 2000.0)),
            listOf(close("7203", 2500.0, "2026-09-17"), close("6758", 2500.0, "2026-09-12")),
        )
        assertEquals("2026-09-12", state.asOfDate)
        assertTrue(state.mixedDates)
        assertTrue(state.render().contains("(最古)"))
    }

    // --- 終値が無い建玉(設計原則1) ---

    @Test fun `終値未入力の建玉は判定に数えない`() {
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2000.0), pos("6758", sl = 2000.0)),
            listOf(close("7203", 2500.0)),
        )
        assertEquals(2, state.positions)
        assertEquals(1, state.evaluated)
        assertEquals(1, state.unpriced)
        assertTrue(state.render().contains("未入力1"))
    }

    @Test fun `終値が1件も無い日は数値を作らない`() {
        val state = SlWatch.evaluate(listOf(pos("7203", sl = 2000.0)), emptyList())
        assertEquals("SL 建玉1 / 終値未入力", state.render())
        assertNull(state.minDistancePct)
        assertNull(state.asOfDate)
    }

    @Test fun `割れ0件と未判定を混同しない`() {
        // 6.2章の展開条件に渡す値。判定できていなければ null(= 畳むだけ)。
        val unpriced = SlWatch.evaluate(listOf(pos("7203", sl = 2000.0)), emptyList())
        val safe = SlWatch.evaluate(listOf(pos("7203", sl = 2000.0)), listOf(close("7203", 2500.0)))
        assertNull(unpriced.breachesOrNull)
        assertEquals(0, safe.breachesOrNull)
        assertEquals(SlotDisplay.COLLAPSED,
            DisplayControl.variableSlot(DisplaySettings(), unpriced.breachesOrNull, unpriced.minDistancePct))
    }

    @Test fun `建玉0件`() {
        assertEquals("SL 建玉0", SlWatch.evaluate(emptyList(), emptyList()).render())
    }

    @Test fun `同じ銘柄に複数日の終値があれば新しい方を使う`() {
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2800.0)),
            listOf(close("7203", 2750.0, "2026-09-16"), close("7203", 2900.0, "2026-09-17")),
        )
        assertEquals(0, state.breachCount)
        assertEquals("2026-09-17", state.asOfDate)
    }

    @Test fun `割れが複数あれば深い順に並ぶ`() {
        val state = SlWatch.evaluate(
            listOf(pos("7203", sl = 2800.0), pos("6758", sl = 3000.0)),
            listOf(close("7203", 2790.0), close("6758", 2000.0)),
        )
        assertEquals(listOf("6758", "7203"), state.breaches.map { it.code })
    }
}
