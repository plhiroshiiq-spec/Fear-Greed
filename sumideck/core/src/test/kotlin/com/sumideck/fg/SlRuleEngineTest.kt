package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlRuleEngineTest {

    private fun pos(rule: SlRule, sl: Double = 900.0, entry: Double = 1000.0) =
        Position("7203", entry = entry, sl = sl, shares = 100, openedAt = "2026-09-01", slRule = rule)

    // --- 9.1章の絶対条件 ---

    @Test fun `SLを自動で下げることは絶対にしない`() {
        // すべての規則で、候補が現在のSLを下回っても下がらないこと。
        val cases = listOf(
            // BREAKEVEN: 建値 500 < 現在SL 900
            pos(SlRule.BREAKEVEN, sl = 900.0, entry = 500.0) to listOf(400.0, 600.0),
            // SWING: スイング安値 100 < 現在SL 900
            pos(SlRule.SWING, sl = 900.0) to listOf(300.0, 100.0, 300.0, 250.0, 280.0),
        )
        cases.forEach { (position, closes) ->
            val update = SlRuleEngine.recalculate(position, closes = closes)
            assertTrue(update.newSl >= position.sl, "${position.slRule}: ${update.newSl} < ${position.sl}")
            assertEquals(900.0, update.newSl)
            assertTrue(!update.changed)
        }
    }

    @Test fun `ATRでもSLは下がらない`() {
        val ohlc = (0 until 30).map { Ohlc("2026-09-%02d".format(it + 1), 520.0, 480.0, 500.0) }
        val update = SlRuleEngine.recalculate(pos(SlRule.ATR, sl = 900.0), ohlc = ohlc)
        assertEquals(900.0, update.newSl)
    }

    @Test fun `既定のNONEは何もしない`() {
        val p = pos(SlRule.NONE)
        val update = SlRuleEngine.recalculate(p, closes = listOf(1500.0, 1600.0))
        assertEquals(p.sl, update.newSl)
        assertTrue(!update.changed)
        assertEquals(SlRule.NONE, Position("7203", 1.0, 1.0, 1, "2026-09-01").slRule)
    }

    // --- 各規則 ---

    @Test fun `BREAKEVENは含み益が出た日だけ建値まで上げる`() {
        val p = pos(SlRule.BREAKEVEN, sl = 900.0, entry = 1000.0)
        assertEquals(1000.0, SlRuleEngine.recalculate(p, closes = listOf(1100.0)).newSl)
        // 建値以下の日は動かさない
        assertEquals(900.0, SlRuleEngine.recalculate(p, closes = listOf(1000.0)).newSl)
        assertEquals(900.0, SlRuleEngine.recalculate(p, closes = listOf(950.0)).newSl)
        // 終値が無ければ何もしない
        assertEquals(900.0, SlRuleEngine.recalculate(p, closes = emptyList()).newSl)
    }

    @Test fun `SWINGは直近の確定スイング安値まで上げる`() {
        // 5.3.1章のスイング判定をそのまま使う。当日と前日は確定扱いしない。
        val closes = listOf(1000.0, 950.0, 1100.0, 980.0, 1200.0, 1150.0, 1180.0)
        val update = SlRuleEngine.recalculate(pos(SlRule.SWING, sl = 900.0), closes = closes)
        assertEquals(980.0, update.newSl)   // index 3 の確定スイング安値
    }

    @Test fun `SWINGはスイング点が無い期間は何もしない`() {
        val monotone = (0 until 20).map { 1000.0 + it }
        assertEquals(900.0, SlRuleEngine.recalculate(pos(SlRule.SWING, sl = 900.0), closes = monotone).newSl)
    }

    @Test fun `ATRは期間ぶんのデータが無ければ何もしない`() {
        val short = (0 until 5).map { Ohlc("2026-09-0$it", 1100.0, 1000.0, 1050.0) }
        assertNull(SlRuleEngine.atrValue(short, 14))
        assertEquals(900.0, SlRuleEngine.recalculate(pos(SlRule.ATR, sl = 900.0), ohlc = short).newSl)
    }

    @Test fun `ATRの値が算出できる`() {
        // 毎日 high-low = 100 の系列なら ATR は 100 に収束する。
        val ohlc = (0 until 30).map { Ohlc("2026-09-%02d".format(it + 1), 1100.0, 1000.0, 1050.0) }
        assertEquals(100.0, SlRuleEngine.atrValue(ohlc, 14)!!, 0.001)
    }

    @Test fun `ATRは終値からATRの倍数を引いた位置に上げる`() {
        val ohlc = (0 until 30).map { Ohlc("2026-09-%02d".format(it + 1), 1100.0, 1000.0, 1050.0) }
        // 1050 − 2.0 × 100 = 850。現在SL 800 より上なので上がる。
        val update = SlRuleEngine.recalculate(pos(SlRule.ATR, sl = 800.0), ohlc = ohlc)
        assertEquals(850.0, update.newSl, 0.001)
        assertTrue(update.changed)
    }

    @Test fun `複数建玉をまとめて再計算できる`() {
        val positions = listOf(
            Position("7203", 1000.0, 900.0, 100, "2026-09-01", SlRule.BREAKEVEN),
            Position("6758", 2000.0, 1800.0, 50, "2026-09-01", SlRule.NONE),
        )
        val updates = SlRuleEngine.recalculateAll(
            positions,
            closesByCode = mapOf("7203" to listOf(1100.0), "6758" to listOf(2500.0)),
        )
        assertEquals(1000.0, updates[0].newSl)   // 建値へ
        assertEquals(1800.0, updates[1].newSl)   // NONE は動かない
    }
}
