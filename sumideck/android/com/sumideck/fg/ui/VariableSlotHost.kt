package com.sumideck.fg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sumideck.fg.DisplaySettings
import com.sumideck.fg.ExtFailurePolicy
import com.sumideck.fg.ExtState
import com.sumideck.fg.SlWatchState
import com.sumideck.fg.SlotDisplay
import com.sumideck.fg.VariableSlotContent
import com.sumideck.fg.VariableSlotPlanner

/**
 * 可変枠の中身を1つだけ出す。SPEC.md 9章。
 *
 * 何を出すかの判断は [VariableSlotPlanner](純Kotlin、テスト済み)が持つ。ここは描くだけ。
 */
@Composable
fun VariableSlotHost(
    settings: DisplaySettings,
    preferred: VariableSlotContent,
    slWatch: SlWatchState?,
    ext: ExtState?,
    modifier: Modifier = Modifier,
    extFailurePolicy: ExtFailurePolicy = ExtFailurePolicy.KEEP_SLOT,
) {
    val plan = VariableSlotPlanner.plan(
        settings = settings,
        preferred = preferred,
        slBreaches = slWatch?.breachesOrNull,
        slMinDistancePct = slWatch?.minDistancePct,
        extUsable = ext?.usable == true,
        extFailurePolicy = extFailurePolicy,
    )
    if (plan.display == SlotDisplay.HIDDEN) return

    Column(modifier = modifier.fillMaxWidth()) {
        when (plan.content) {
            VariableSlotContent.SL_WATCH ->
                slWatch?.let { SlWatchPanel(it, collapsed = plan.display == SlotDisplay.COLLAPSED) }

            VariableSlotContent.EXT ->
                ext?.let { ExtPanel(it) }

            VariableSlotContent.NONE -> Unit
        }
    }
}
