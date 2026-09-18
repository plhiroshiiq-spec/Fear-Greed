package com.sumideck.fg.ui

import androidx.compose.ui.graphics.Color

/**
 * SPEC.md 5.3.5章 / 5.2章。
 *
 * **色を使うのは自動描画の線と凡例だけ。** 推移線・数値・区分名・位置の帯は白と灰のまま。
 * これにより「白 = 実際のデータ、色 = プログラムが引いた解釈」という区別が成立する。
 * 区分の強弱を色で表すことは禁止(5.2章)。
 *
 * SUMI DECK に取り込む際は、既存テーマに同等のトークンがあればそちらを優先すること(5章)。
 */
object FgColors {
    /** 実データ。白。 */
    val foreground = Color(0xFFF2F2F2)
    val textSecondary = Color(0xFFA8A8A8)
    val textMuted = Color(0xFF6E6E6E)

    /** 位置の帯。全区分同じ濃度にする(色で区分を表さないため)。 */
    val band = Color(0xFF3A3A3A)
    val background = Color(0xFF0D0D0D)

    // --- ここから下だけが色。自動描画の線と凡例専用。 ---
    val resistance = Color(0xFFE08A3C)   // 抵抗 = オレンジ系
    val support = Color(0xFF4FA96B)      // 支持 = 緑系
    val trend = Color(0xFF9B6BD6)        // 斜線 = 紫系
}
