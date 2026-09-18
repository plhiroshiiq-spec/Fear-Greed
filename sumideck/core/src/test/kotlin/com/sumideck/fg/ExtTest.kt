package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtTest {

    private fun extJson(
        changePct: Double? = -1.5,
        stale: Boolean = false,
        asOf: String = "2026-09-17",
        generatedAt: String = "2026-09-18T06:41:12+09:00",
    ) = """
        {"schema":1,"generated_at":"$generatedAt",
         "ext":{"key":"sox","name_ja":"SOX","source":"stooq",
                "as_of":"$asOf","change_pct":${changePct ?: "null"},"stale":$stale}}
    """.trimIndent()

    @Test fun `使える日は前日比を出す`() {
        val state = ExtBuilder.build(extJson())
        assertTrue(state.usable)
        assertEquals(-1.5, state.changePct)
        assertEquals("SOX −1.5% 09/17", state.render())
    }

    @Test fun `staleの日は値そのものを渡さない`() {
        // 9.2章「誤った値は絶対に出さない」。
        val state = ExtBuilder.build(extJson(stale = true))
        assertTrue(!state.usable)
        assertNull(state.changePct)
        assertEquals("SOX — 取得できず", state.render())
    }

    @Test fun `change_pctが無ければ使えない`() {
        assertTrue(!ExtBuilder.build(extJson(changePct = null)).usable)
    }

    @Test fun `ext_jsonが無い日もクラッシュしない`() {
        val state = ExtBuilder.build(null)
        assertTrue(!state.usable)
        assertEquals("SOX", state.nameJa)
        assertEquals("SOX — 取得できず", state.render())
    }

    @Test fun `壊れたJSONでも落ちない`() {
        assertTrue(!ExtBuilder.build("{ broken").usable)
    }

    @Test fun `generated_atが36時間より古ければ使えない`() {
        val generated = "2026-09-18T06:41:12+09:00"
        val epoch = IsoTime.toEpochSecondsOrNull(generated)!!
        assertTrue(ExtBuilder.build(extJson(generatedAt = generated), epoch + 35 * 3600).usable)
        assertTrue(!ExtBuilder.build(extJson(generatedAt = generated), epoch + 37 * 3600).usable)
    }

    @Test fun `プラスの変化には符号が付く`() {
        assertEquals("SOX +2.3% 09/17", ExtBuilder.build(extJson(changePct = 2.3)).render())
    }
}

class VariableSlotPlannerTest {

    private val settings = DisplaySettings()

    @Test fun `手動オフが最優先`() {
        val plan = VariableSlotPlanner.plan(
            DisplaySettings(variableSlotVisible = false), VariableSlotContent.EXT, extUsable = true)
        assertEquals(SlotDisplay.HIDDEN, plan.display)
    }

    @Test fun `EXTが使える日はそのまま出す`() {
        val plan = VariableSlotPlanner.plan(settings, VariableSlotContent.EXT, extUsable = true)
        assertEquals(VariableSlotContent.EXT, plan.content)
        assertEquals(SlotDisplay.FULL, plan.display)
    }

    @Test fun `既定ではEXTが使えなくても枠を消さない`() {
        // 6.1章 / 6.4章。値は出さないが枠は残す。
        val plan = VariableSlotPlanner.plan(settings, VariableSlotContent.EXT, extUsable = false)
        assertEquals(SlotDisplay.COLLAPSED, plan.display)
    }

    @Test fun `HIDE_SLOTを選べば9_2章の字面どおり枠ごと消える`() {
        val plan = VariableSlotPlanner.plan(
            settings, VariableSlotContent.EXT, extUsable = false,
            extFailurePolicy = ExtFailurePolicy.HIDE_SLOT)
        assertEquals(SlotDisplay.HIDDEN, plan.display)
    }

    @Test fun `SL WATCHを選べば6_2章の展開条件に従う`() {
        assertEquals(SlotDisplay.FULL,
            VariableSlotPlanner.plan(settings, VariableSlotContent.SL_WATCH, slBreaches = 1).display)
        assertEquals(SlotDisplay.COLLAPSED,
            VariableSlotPlanner.plan(settings, VariableSlotContent.SL_WATCH,
                slBreaches = 0, slMinDistancePct = 9.0).display)
    }

    @Test fun `可変枠は同時に1つだけ`() {
        // 9章。plan は必ず1つの content しか返さない。
        listOf(VariableSlotContent.SL_WATCH, VariableSlotContent.EXT, VariableSlotContent.NONE)
            .forEach { preferred ->
                val plan = VariableSlotPlanner.plan(settings, preferred, slBreaches = 0, extUsable = true)
                assertTrue(plan.content == preferred || plan.content == VariableSlotContent.NONE)
            }
    }

    @Test fun `NONEなら何も出さない`() {
        assertEquals(SlotDisplay.HIDDEN,
            VariableSlotPlanner.plan(settings, VariableSlotContent.NONE).display)
    }
}
