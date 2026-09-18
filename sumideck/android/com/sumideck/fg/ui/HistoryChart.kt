package com.sumideck.fg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.sumideck.fg.ChartScale
import com.sumideck.fg.HorizontalLine
import com.sumideck.fg.Overlay
import com.sumideck.fg.PanelState
import com.sumideck.fg.TrendLine

/**
 * 3段目 推移線と補助線。SPEC.md 5.1章 / 5.3章。
 *
 * - 直近60営業日の折れ線。高さ88dp、線幅1.8dp、色は前景色(白)、結合は丸め。
 * - Y軸は表示区間(推移線と描画中の補助線を含む)の最小値−4 と 最大値+4。
 * - 最新点にだけ半径3.8dpの点。
 * - `history` が60本に満たない場合はある分だけ描く。**足りない分を0や前値で埋めない。**
 * - 補助線の色は 5.3.5章の例外(抵抗=オレンジ / 支持=緑 / 斜線=紫)。
 */
@Composable
fun HistoryChart(state: PanelState, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(FgDimens.chartHeight)
    ) {
        val scale = state.scale ?: return@Canvas
        val series = state.series
        if (series.isEmpty()) return@Canvas

        // 最新点の丸(半径3.8dp)が右端で欠けないよう、描画域を半径ぶん内側に取る。
        // Canvas は既定でクリップするので、右端ちょうどに打つと丸の右半分が消える。
        val dotRadius = FgDimens.latestDotRadius.toPx()
        val right = (size.width - dotRadius).coerceAtLeast(0f)

        // 1本しか無い日は中央に点だけ打つ。0除算を避ける。
        val stepX = if (series.size > 1) right / (series.size - 1) else 0f
        fun x(index: Int): Float = if (series.size > 1) stepX * index else size.width / 2f
        fun y(value: Double): Float = (size.height * (1.0 - scale.normalize(value))).toFloat()

        // 補助線は推移線の下に敷く(実データを隠さない)。
        drawOverlay(state.overlay, series.size, ::x, ::y, scale)

        if (series.size > 1) {
            val path = Path().apply {
                moveTo(x(0), y(series[0]))
                for (i in 1 until series.size) lineTo(x(i), y(series[i]))
            }
            drawPath(
                path = path,
                color = FgColors.foreground,
                style = Stroke(
                    width = FgDimens.lineWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        drawCircle(
            color = FgColors.foreground,
            radius = dotRadius,
            center = Offset(x(series.lastIndex), y(series.last())),
        )
    }
}

private fun DrawScope.drawOverlay(
    overlay: Overlay,
    seriesSize: Int,
    x: (Int) -> Float,
    y: (Double) -> Float,
    @Suppress("UNUSED_PARAMETER") scale: ChartScale,
) {
    overlay.support?.let { drawHorizontal(it, FgColors.support, x, y, seriesSize) }
    overlay.resistance?.let { drawHorizontal(it, FgColors.resistance, x, y, seriesSize) }

    listOfNotNull(overlay.falling, overlay.rising).forEach { line ->
        drawTrend(line, x, y, seriesSize)
    }

    overlay.markers.forEach { marker ->
        drawCircle(
            color = FgColors.trend,
            radius = FgDimens.breakMarkerRadius.toPx(),
            center = Offset(x(marker.index), y(marker.value)),
        )
    }
}

/**
 * 5.3.2章: 起点より左は同色・線幅2dp・不透明度28%で薄く、
 * 起点から右端までは線幅3dp・破線(7dp描画 / 4dp空白・端は丸め)。
 * 起点に半径3.2dpの円マーカー(線と同色、中は背景色で抜く)。
 */
private fun DrawScope.drawHorizontal(
    line: HorizontalLine,
    color: androidx.compose.ui.graphics.Color,
    x: (Int) -> Float,
    y: (Double) -> Float,
    seriesSize: Int,
) {
    val yy = y(line.value)
    val originX = x(line.originIndex)

    if (line.originIndex > 0) {
        drawLine(
            color = color.copy(alpha = FgDimens.horizontalPastAlpha),
            start = Offset(0f, yy),
            end = Offset(originX, yy),
            strokeWidth = FgDimens.horizontalPastWidth.toPx(),
        )
    }

    drawLine(
        color = color,
        start = Offset(originX, yy),
        end = Offset(x(seriesSize - 1), yy),
        strokeWidth = FgDimens.horizontalActiveWidth.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(FgDimens.dashOn.toPx(), FgDimens.dashOff.toPx()),
            0f,
        ),
    )

    val r = FgDimens.originMarkerRadius.toPx()
    drawCircle(color = FgColors.background, radius = r, center = Offset(originX, yy))
    drawCircle(
        color = color,
        radius = r,
        center = Offset(originX, yy),
        style = Stroke(width = 1.5f),
    )
}

/** 5.3.3章: 紫系・線幅3dp・実線。3点目の位置から右端まで延長する。 */
private fun DrawScope.drawTrend(
    line: TrendLine,
    x: (Int) -> Float,
    y: (Double) -> Float,
    seriesSize: Int,
) {
    val from = line.pointIndices.first()
    val to = seriesSize - 1
    drawLine(
        color = FgColors.trend,
        start = Offset(x(from), y(line.valueAt(from))),
        end = Offset(x(to), y(line.valueAt(to))),
        strokeWidth = FgDimens.trendWidth.toPx(),
        cap = StrokeCap.Round,
    )
}
