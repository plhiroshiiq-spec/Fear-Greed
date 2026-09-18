package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingTest {

    @Test fun `丸めは切り捨て`() {
        // P1で確定した規則。docs/rounding.md の実測値をそのまま回帰テストにする。
        assertEquals(28, Display.toInt(28.69))
        assertEquals(32, Display.toInt(32.69))
        assertEquals(54, Display.toInt(54.63))
        assertEquals(66, Display.toInt(66.54))
        assertEquals(29, Display.toInt(29.6285714285714))
        assertEquals(28, Display.toInt(28.5428571428571))
    }

    @Test fun `四捨五入になっていないこと`() {
        // Math.round 相当なら 29 になる値。
        assertEquals(28, Display.toInt(28.9999))
    }

    @Test fun `区分は境界で切り替わる`() {
        assertEquals(Rating.EXTREME_FEAR, Rating.fromScore(24.99))
        assertEquals(Rating.FEAR, Rating.fromScore(25.0))
        assertEquals(Rating.FEAR, Rating.fromScore(44.99))
        assertEquals(Rating.NEUTRAL, Rating.fromScore(45.0))
        assertEquals(Rating.NEUTRAL, Rating.fromScore(54.99))
        assertEquals(Rating.GREED, Rating.fromScore(55.0))
        assertEquals(Rating.GREED, Rating.fromScore(74.99))
        assertEquals(Rating.EXTREME_GREED, Rating.fromScore(75.0))
    }

    @Test fun `日本語表記はSPECの5語`() {
        assertEquals(
            listOf("極度の恐怖", "恐怖", "中立", "強欲", "極度の強欲"),
            Rating.entries.map { it.ja },
        )
    }

    @Test fun `ratingを優先しscoreは保険`() {
        assertEquals(Rating.FEAR, Rating.resolve("fear", 90.0))
        assertEquals(Rating.EXTREME_GREED, Rating.resolve("panic", 90.0))  // 壊れた rating は score で救う
        assertNull(Rating.resolve(null, null))
    }

    @Test fun `帯の幅は区分の実幅に比例する`() {
        assertEquals(listOf(25.0, 20.0, 10.0, 20.0, 25.0), BANDS.map { it.width })
        assertEquals(100.0, BANDS.sumOf { it.width })
    }

    @Test fun `前日差と連続日数のラベル`() {
        assertEquals("−2", Display.deltaLabel(-2.4))
        assertEquals("+3", Display.deltaLabel(3.7))
        assertEquals("±0", Display.deltaLabel(0.4))
        assertNull(Display.deltaLabel(null))
        assertEquals("↓3日", Display.streakLabel(-3))
        assertEquals("↑2日", Display.streakLabel(2))
        assertNull(Display.streakLabel(0))
    }
}
