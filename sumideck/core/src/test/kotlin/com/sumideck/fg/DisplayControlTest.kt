package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DisplayControlTest {

    private fun state(
        score: Double? = 50.0,
        delta: Double? = 0.0,
        streak: Int = 0,
        stale: Boolean = false,
    ) = PanelBuilder.build(
        FgJson.parseOrNull(Fixtures.doc(score = score, delta = delta, streak = streak, stale = stale))
    )

    // --- 6.1章 手動オン/オフ ---

    @Test fun `手動オフのときだけ枠が消える`() {
        val off = DisplaySettings(usGaugeVisible = false)
        assertEquals(SlotDisplay.HIDDEN, DisplayControl.usGauge(off, state()))
        assertEquals(SlotDisplay.HIDDEN, DisplayControl.variableSlot(
            DisplaySettings(variableSlotVisible = false), breaches = 0, minDistancePct = 9.0))
    }

    @Test fun `手動オフ以外でHIDDENにならない`() {
        // 6.1章「完全非表示はこの手動オフの時だけ」を全組み合わせで確認する。
        val cases = listOf(
            state(), state(stale = true), state(score = null),
            state(score = 10.0), state(score = 90.0), state(delta = -9.0), state(streak = -5),
        )
        for (collapse in listOf(true, false)) {
            val s = DisplaySettings(usCollapseEnabled = collapse)
            for (st in cases) {
                assertTrue(
                    DisplayControl.usGauge(s, st) != SlotDisplay.HIDDEN,
                    "collapse=$collapse score=${st.score} stale=${st.stale}",
                )
            }
        }
    }

    // --- 6.2章 条件付き折りたたみ ---

    @Test fun `既定は常時表示`() {
        val defaults = DisplaySettings()
        assertTrue(!defaults.usCollapseEnabled)          // USゲージは既定OFF
        assertTrue(defaults.slWatchCollapseEnabled)      // SL WATCH は既定ON
        assertEquals(LineMode.HORIZONTAL_ONLY, defaults.lineMode)
        // 平常時(score 50 / delta 0 / streak 0)でも畳まない。
        assertEquals(SlotDisplay.FULL, DisplayControl.usGauge(defaults, state()))
    }

    @Test fun `折りたたみONなら平常時は畳む`() {
        val s = DisplaySettings(usCollapseEnabled = true)
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.usGauge(s, state(score = 50.0, delta = -1.0, streak = 2)))
    }

    @Test fun `折りたたみONでも展開条件に当たれば開く`() {
        val s = DisplaySettings(usCollapseEnabled = true)
        assertEquals(SlotDisplay.FULL, DisplayControl.usGauge(s, state(score = 24.9)))   // < 25
        assertEquals(SlotDisplay.FULL, DisplayControl.usGauge(s, state(score = 75.1)))   // > 75
        assertEquals(SlotDisplay.FULL, DisplayControl.usGauge(s, state(delta = -5.0)))   // |delta| >= 5
        assertEquals(SlotDisplay.FULL, DisplayControl.usGauge(s, state(streak = 3)))     // |streak| >= 3
    }

    // --- 6.4章 消えてはいけないケース ---

    @Test fun `staleでも畳むだけで消さない`() {
        val s = DisplaySettings(usCollapseEnabled = true)
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.usGauge(s, state(stale = true)))
    }

    @Test fun `scoreが無くても畳むだけで消さない`() {
        val s = DisplaySettings(usCollapseEnabled = true)
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.usGauge(s, state(score = null)))
    }

    @Test fun `畳んだ1行には更新遅延と最終取得日が出る`() {
        val row = CollapsedRow.of(state(stale = true))
        assertNotNull(row.staleText)
        assertTrue(row.render().contains("更新遅延"))
    }

    @Test fun `staleのときは展開条件より6_4章が優先する`() {
        // score 10(< 25)で展開条件には当たるが、値が信用できないので畳む。
        val s = DisplaySettings(usCollapseEnabled = true)
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.usGauge(s, state(score = 10.0, stale = true)))
    }

    // --- 6.2章 SL WATCH の展開条件(中身の実装はP4) ---

    @Test fun `SL WATCHは割れ1件以上で開く`() {
        val s = DisplaySettings()
        assertEquals(SlotDisplay.FULL, DisplayControl.variableSlot(s, breaches = 1, minDistancePct = 9.0))
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.variableSlot(s, breaches = 0, minDistancePct = 9.0))
    }

    @Test fun `SL WATCHは最短距離2パーセント未満で開く`() {
        val s = DisplaySettings()
        assertEquals(SlotDisplay.FULL, DisplayControl.variableSlot(s, breaches = 0, minDistancePct = 1.9))
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.variableSlot(s, breaches = 0, minDistancePct = 2.0))
    }

    @Test fun `SL WATCHは判定できていなければ畳むだけ`() {
        val s = DisplaySettings()
        assertEquals(SlotDisplay.COLLAPSED, DisplayControl.variableSlot(s, breaches = null, minDistancePct = null))
    }

    @Test fun `SL WATCHの折りたたみをOFFにすれば常時表示`() {
        val s = DisplaySettings(slWatchCollapseEnabled = false)
        assertEquals(SlotDisplay.FULL, DisplayControl.variableSlot(s, breaches = 0, minDistancePct = 9.0))
    }

    // --- 0章 / 7章 枠を増やさない ---

    @Test fun `常設枠は最大2`() {
        assertEquals(2, DisplayControl.visibleSlotCount(DisplaySettings()))
        assertEquals(DisplayControl.MAX_SLOTS, DisplayControl.visibleSlotCount(DisplaySettings()))
        assertEquals(0, DisplayControl.visibleSlotCount(
            DisplaySettings(usGaugeVisible = false, variableSlotVisible = false)))
    }
}
