package com.sumideck.fg

import kotlin.math.floor

/**
 * 区分と表示の丸め。SPEC.md 1.3章 / 5.1章。
 *
 * 丸め規則は **切り捨て** で確定している(P1、2026-09-18)。根拠は docs/rounding.md。
 * `kotlin.math.round` は使わないこと。
 */
object Display {
    /** CNN画面と揃える整数表示値。切り捨て。 */
    fun toInt(score: Double): Int = floor(score).toInt()

    /** `−2` / `+3` / `±0` の形。SPEC.md 5.1章の数値行と 6.3章の折りたたみ行で使う。 */
    fun deltaLabel(delta: Double?): String? {
        if (delta == null) return null
        val v = toInt(kotlin.math.abs(delta)).let { if (delta < 0) -it else it }
        return when {
            v > 0 -> "+$v"
            v < 0 -> "−${-v}"   // U+2212 MINUS SIGN(ハイフンより見やすい)
            else -> "±0"
        }
    }

    /** `↓3日` / `↑2日`。連続が無い日は null(何も出さない)。 */
    fun streakLabel(streak: Int): String? = when {
        streak > 0 -> "↑${streak}日"
        streak < 0 -> "↓${-streak}日"
        else -> null
    }
}

/** SPEC.md 1.3章の5区分。`rating` 文字列をそのまま使い、日本語表記に変換して表示する。 */
enum class Rating(val cnnKey: String, val ja: String) {
    EXTREME_FEAR("extreme fear", "極度の恐怖"),
    FEAR("fear", "恐怖"),
    NEUTRAL("neutral", "中立"),
    GREED("greed", "強欲"),
    EXTREME_GREED("extreme greed", "極度の強欲");

    companion object {
        fun fromKey(key: String?): Rating? =
            entries.firstOrNull { it.cnnKey.equals(key?.trim(), ignoreCase = true) }

        /** `rating` が壊れていた場合にだけ使う保険。区分の境界は 25 / 45 / 55 / 75。 */
        fun fromScore(score: Double): Rating = when {
            score < 25.0 -> EXTREME_FEAR
            score < 45.0 -> FEAR
            score < 55.0 -> NEUTRAL
            score < 75.0 -> GREED
            else -> EXTREME_GREED
        }

        /** 表示用。`rating` を優先し、無ければ score から判定する。どちらも無ければ null。 */
        fun resolve(key: String?, score: Double?): Rating? =
            fromKey(key) ?: score?.let { fromScore(it) }
    }
}

/** SPEC.md 5.1章 2段目「位置の帯」の5区分。幅は区分の実幅に比例させる。 */
data class Band(val rating: Rating, val from: Double, val to: Double) {
    val width: Double get() = to - from
}

val BANDS: List<Band> = listOf(
    Band(Rating.EXTREME_FEAR, 0.0, 25.0),
    Band(Rating.FEAR, 25.0, 45.0),
    Band(Rating.NEUTRAL, 45.0, 55.0),
    Band(Rating.GREED, 55.0, 75.0),
    Band(Rating.EXTREME_GREED, 75.0, 100.0),
)

/** 帯の下に出す目盛り。SPEC.md 5.1章。 */
val BAND_TICKS: List<Int> = listOf(0, 25, 45, 55, 75, 100)

/** スコアを帯全体に対する 0..1 の位置に直す。範囲外は端に丸める。 */
fun bandPosition(score: Double): Double = (score / 100.0).coerceIn(0.0, 1.0)
