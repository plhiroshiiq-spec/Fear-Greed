package com.sumideck.fg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sumideck.fg.Display
import com.sumideck.fg.HorizontalLine
import com.sumideck.fg.LineMode
import com.sumideck.fg.PanelState
import com.sumideck.fg.TrendLine

/**
 * 5.3.4章 凡例。推移線の下に12spで置く。**描画中の線だけを出す。**
 *
 * ```
 * — 下降線 上抜け   — 抵抗 32.7   — 支持 27.3
 * ```
 */
@Composable
fun OverlayLegend(state: PanelState, lineMode: LineMode, modifier: Modifier = Modifier) {
    if (lineMode == LineMode.NONE || state.overlay.isEmpty) return
    val o = state.overlay

    val items = buildList {
        o.falling?.let { add(FgColors.trend to "下降線${breakSuffix(state, TrendLine.Kind.FALLING)}") }
        o.rising?.let { add(FgColors.trend to "上昇線${breakSuffix(state, TrendLine.Kind.RISING)}") }
        o.resistance?.let { add(FgColors.resistance to "抵抗 ${format(it)}") }
        o.support?.let { add(FgColors.support to "支持 ${format(it)}") }
    }
    if (items.isEmpty()) return

    Row(
        modifier = modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(color)
                Spacer(Modifier.width(4.dp))
                FgText(label, FgDimens.legendSize, FgColors.textSecondary)
            }
        }
    }
}

/** 凡例の `—`。線と同じ色を使う(5.3.5章の色の例外)。 */
@Composable
private fun Swatch(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.width(12.dp).height(3.dp)) {
        drawRect(color = color)
    }
}

/**
 * 抜けたばかりの線には「上抜け」「下抜け」を添える。
 * マーカーは3営業日残る(5.3.3章)ので、その間だけ出る。
 */
private fun breakSuffix(state: PanelState, kind: TrendLine.Kind): String {
    val hit = state.overlay.markers.any { it.kind == kind }
    if (!hit) return ""
    return if (kind == TrendLine.Kind.FALLING) " 上抜け" else " 下抜け"
}

/** 凡例には小数第1位まで出す(`抵抗 32.7`)。5.3.4章の例に合わせる。 */
private fun format(line: HorizontalLine): String {
    val scaled = kotlin.math.round(line.value * 10.0) / 10.0
    return if (scaled == scaled.toInt().toDouble()) "${scaled.toInt()}.0" else scaled.toString()
}
