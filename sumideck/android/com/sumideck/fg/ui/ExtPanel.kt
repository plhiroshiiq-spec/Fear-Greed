package com.sumideck.fg.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sumideck.fg.ExtState
import com.sumideck.fg.SlWatchState

/**
 * 可変枠 EXT。SPEC.md 9.2章。
 *
 * > 寄り前の外部環境を**1つだけ**。既定はSOX指数の前日比%。
 *
 * 1行しか持たない。ここに指数を足したくなったら 7章の除外リストを読み直すこと。
 * 上下を色で表さない(5.2章のモノクロ規定)。符号と数字で表す。
 */
@Composable
fun ExtPanel(state: ExtState, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        FgText(state.nameJa, FgDimens.deltaSize, FgColors.textSecondary)
        Spacer(Modifier.width(10.dp))
        if (state.usable && state.changePct != null) {
            FgText(
                SlWatchState.formatPct(state.changePct),
                FgDimens.ratingSize,
                FgColors.foreground,
            )
            Spacer(Modifier.weight(1f))
            state.asOfLabel?.let { FgText(it, FgDimens.captionSize, FgColors.textMuted) }
        } else {
            // 9.2章「誤った値は絶対に出さない」。数値は出さず、来ていないことだけ伝える。
            FgText("— 取得できず", FgDimens.deltaSize, FgColors.textMuted)
        }
    }
}
