package com.sumideck.fg.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SPEC.md 5.1章 / 5.3章の実装寸法。基準幅334dp(画面幅390 − 左右パディング28)。 */
object FgDimens {
    val panelWidth = 334.dp

    // 1段目 数値行
    val scoreSize = 46.sp
    val ratingSize = 16.sp
    val deltaSize = 14.sp

    // 2段目 位置の帯(数値行の16dp下)
    val bandTopGap = 16.dp
    val bandHeight = 6.dp
    val bandGap = 2.dp
    val markerWidth = 3.dp
    val markerHeight = 18.dp
    val tickSize = 11.sp

    // 3段目 推移線(帯の26dp下)
    val chartTopGap = 26.dp
    val chartHeight = 88.dp
    val lineWidth = 1.8.dp
    val latestDotRadius = 3.8.dp
    val captionSize = 11.sp

    // 5.3.2 水平線
    val horizontalPastWidth = 2.dp
    val horizontalActiveWidth = 3.dp
    val horizontalPastAlpha = 0.28f
    val dashOn = 7.dp
    val dashOff = 4.dp
    val originMarkerRadius = 3.2.dp

    // 5.3.3 斜線
    val trendWidth = 3.dp
    val breakMarkerRadius = 5.5.dp

    // 5.3.4 凡例
    val legendSize = 12.sp
}
