package com.sumideck.fg.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * 4段目(展開時)の7要素の横棒。SPEC.md 5.1章。
 * **色で強弱を表さない**(5.2章)。濃度と長さだけで表す。
 */
@Composable
fun ComponentBar(score: Double?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(120.dp).height(4.dp)) {
        drawRect(color = FgColors.band)
        if (score == null) return@Canvas
        val w = size.width * (score.coerceIn(0.0, 100.0) / 100.0).toFloat()
        drawRect(
            color = FgColors.foreground,
            topLeft = Offset(0f, 0f),
            size = Size(w, size.height),
        )
    }
}
