package com.sumideck.fg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sumideck.fg.SlWatchState

/**
 * 可変枠 SL WATCH。SPEC.md 9.1章。
 *
 * 表示: `SL 建玉5 / 割れ1 (7203) 最短 +1.2% 09/17終値`
 * **判定に使った終値の日付を必ず添える。**
 *
 * 5.2章のモノクロ規定に従い、割れを赤で表さない。濃度と位置とテキストで表す。
 * 色の例外は 5.3.5章の自動描画ラインだけ。
 */
@Composable
fun SlWatchPanel(
    state: SlWatchState,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
) {
    if (collapsed) {
        FgText(state.render(), FgDimens.deltaSize, FgColors.textSecondary, modifier.fillMaxWidth())
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            FgText("SL", FgDimens.ratingSize, FgColors.textSecondary)
            Spacer(Modifier.width(10.dp))
            FgText("建玉${state.positions}", FgDimens.ratingSize, FgColors.foreground)
            Spacer(Modifier.width(10.dp))
            FgText(
                if (state.evaluated == 0) "終値未入力" else "割れ${state.breachCount}",
                FgDimens.ratingSize,
                FgColors.foreground,
            )
            Spacer(Modifier.weight(1f))
            state.asOfDate?.let {
                FgText(
                    "${SlWatchState.shortDate(it)}終値" + if (state.mixedDates) "(最古)" else "",
                    FgDimens.captionSize,
                    FgColors.textMuted,
                )
            }
        }

        if (state.evaluated > 0) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.breaches.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FgText(b.code, FgDimens.deltaSize, FgColors.foreground, Modifier.width(64.dp))
                        FgText("割れ", FgDimens.captionSize, FgColors.textSecondary)
                        Spacer(Modifier.width(8.dp))
                        FgText(
                            "${trimNumber(b.close)} < SL ${trimNumber(b.sl)}",
                            FgDimens.captionSize,
                            FgColors.textSecondary,
                        )
                    }
                }
                state.minDistancePct?.let {
                    FgText(
                        "最短 ${SlWatchState.formatPct(it)}" +
                            (state.minDistanceCode?.let { c -> " ($c)" } ?: ""),
                        FgDimens.captionSize,
                        FgColors.textSecondary,
                    )
                }
            }
        }

        // 未入力を黙って隠さない(設計原則1)。
        if (state.unpriced > 0) {
            Spacer(Modifier.height(6.dp))
            FgText("終値未入力 ${state.unpriced}件", FgDimens.captionSize, FgColors.textMuted)
        }
    }
}

private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
