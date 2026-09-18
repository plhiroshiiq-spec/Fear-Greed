package com.sumideck.fg.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sumideck.fg.DisplayControl
import com.sumideck.fg.DisplaySettings
import com.sumideck.fg.ExtFailurePolicy
import com.sumideck.fg.ExtState
import com.sumideck.fg.PanelState
import com.sumideck.fg.SlWatchState
import com.sumideck.fg.SlotDisplay
import com.sumideck.fg.VariableSlotContent

/**
 * ホーム画面の常設枠。SPEC.md 0章 / 6章 / 7章 / 9章。
 *
 * **常設はUSゲージ1枚 + 可変1枠の計2枠。枠を増やさない。**
 * 可変枠は SL WATCH(9.1章)か EXT(9.2章)の**どちらか1つだけ**。
 * 7章の除外リスト(VIX単独表示・個別銘柄・ニュース・日本市場の指標)をここに足さないこと。
 *
 * 出し分けの判断は [DisplayControl] と [com.sumideck.fg.VariableSlotPlanner]
 * (どちらも純Kotlin、テスト済み)が持つ。ここは結果を描くだけ。
 */
@Composable
fun FgHome(
    state: PanelState,
    settings: DisplaySettings,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    variableSlot: VariableSlotContent = VariableSlotContent.SL_WATCH,
    slWatch: SlWatchState? = null,
    ext: ExtState? = null,
    extFailurePolicy: ExtFailurePolicy = ExtFailurePolicy.KEEP_SLOT,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (DisplayControl.usGauge(settings, state)) {
            SlotDisplay.HIDDEN -> Unit                      // 手動オフの時だけここに来る
            SlotDisplay.COLLAPSED -> FearGreedPanel(state, collapsed = true, lineMode = settings.lineMode)
            SlotDisplay.FULL -> FearGreedPanel(state, expanded = expanded, lineMode = settings.lineMode)
        }

        Spacer(Modifier.height(20.dp))
        VariableSlotHost(
            settings = settings,
            preferred = variableSlot,
            slWatch = slWatch,
            ext = ext,
            extFailurePolicy = extFailurePolicy,
        )
    }
}
