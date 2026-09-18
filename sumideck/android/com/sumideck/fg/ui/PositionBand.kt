package com.sumideck.fg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.sumideck.fg.BANDS
import com.sumideck.fg.BAND_TICKS
import com.sumideck.fg.PanelState

/**
 * 2段目 位置の帯。SPEC.md 5.1章。
 *
 * - 高さ6dpの帯を5区分に分割。幅は区分の実幅に比例(0–25 / 25–45 / 45–55 / 55–75 / 75–100)。
 * - 区分間に2dpの隙間。
 * - 現在位置マーカー: 幅3dp・高さ18dp、帯の上下にはみ出す。色は前景色(白)。
 * - **帯そのものは全区分同じ濃度**にし、色ではなくマーカー位置だけで現在地を示す(モノクロのため)。
 */
@Composable
fun PositionBand(state: PanelState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // マーカーが帯の上下にはみ出すぶんの高さを確保する。
                .height(FgDimens.markerHeight)
        ) {
            val gap = FgDimens.bandGap.toPx()
            val bandHeight = FgDimens.bandHeight.toPx()
            val totalGap = gap * (BANDS.size - 1)
            val usable = size.width - totalGap
            val bandTop = (size.height - bandHeight) / 2f

            var x = 0f
            BANDS.forEach { band ->
                val w = usable * (band.width / 100.0).toFloat()
                drawRect(
                    color = FgColors.band,          // 全区分同じ濃度
                    topLeft = Offset(x, bandTop),
                    size = Size(w, bandHeight),
                )
                x += w + gap
            }

            state.markerPosition?.let { pos ->
                val markerW = FgDimens.markerWidth.toPx()
                val markerH = FgDimens.markerHeight.toPx()
                // 隙間を含めた帯全体の幅に対して位置を取る。
                val cx = (usable * pos.toFloat()) + gap * bandIndexFor(pos)
                drawRect(
                    color = FgColors.foreground,
                    topLeft = Offset((cx - markerW / 2f).coerceIn(0f, size.width - markerW), 0f),
                    size = Size(markerW, markerH),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            // 0 / 25 / 45 / 55 / 75 / 100 を両端揃えで置く。
            BAND_TICKS.forEachIndexed { i, tick ->
                FgText(tick.toString(), FgDimens.tickSize, FgColors.textMuted)
                if (i != BAND_TICKS.lastIndex) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** マーカーが何本目の隙間より右にいるか。位置を隙間ぶんずらすために使う。 */
private fun bandIndexFor(position: Double): Int {
    val score = position * 100.0
    var passed = 0
    BANDS.forEach { if (score > it.to) passed++ }
    return passed
}
