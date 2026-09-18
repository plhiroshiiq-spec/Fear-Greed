package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingChangeTest {

    private fun state(vararg scores: Double, stale: Boolean = false): PanelState {
        val history = Fixtures.businessSeries(scores.size) { scores[it] }
        return PanelBuilder.build(
            FgJson.parseOrNull(Fixtures.doc(score = scores.last(), stale = stale, history = history))
        )
    }

    @Test fun `区分が変わった日だけ通知する`() {
        // 恐怖(44) → 中立(46)
        val change = RatingChange.detect(state(40.0, 44.0, 46.0))
        assertNotNull(change)
        assertEquals(Rating.FEAR, change.from)
        assertEquals(Rating.NEUTRAL, change.to)
        assertEquals("恐怖 → 中立 46", change.render())
    }

    @Test fun `区分が変わらない日は通知しない`() {
        assertNull(RatingChange.detect(state(40.0, 42.0, 44.0)))
    }

    @Test fun `大きく動いても同じ区分なら通知しない`() {
        // 26 → 44 はどちらも「恐怖」。
        assertNull(RatingChange.detect(state(30.0, 26.0, 44.0)))
    }

    @Test fun `staleの日は通知しない`() {
        // 値が信用できない日に鳴らさない(設計原則1)。
        assertNull(RatingChange.detect(state(40.0, 44.0, 46.0, stale = true)))
    }

    @Test fun `同じ日には2度通知しない`() {
        val s = state(40.0, 44.0, 46.0)
        val first = assertNotNull(RatingChange.detect(s))
        assertNull(RatingChange.detect(s, lastNotifiedAsOf = first.asOf))
    }

    @Test fun `履歴が2本未満なら通知しない`() {
        assertNull(RatingChange.detect(state(46.0)))
    }

    @Test fun `悪化方向が分かる`() {
        assertTrue(RatingChange.detect(state(50.0, 46.0, 44.0))!!.towardFear)      // 中立 → 恐怖
        assertTrue(!RatingChange.detect(state(40.0, 44.0, 46.0))!!.towardFear)     // 恐怖 → 中立
    }

    @Test fun `境界をまたぐ日を全部拾える`() {
        val series = listOf(24.0, 26.0, 44.0, 46.0, 54.0, 56.0, 74.0, 76.0)
        val found = RatingChange.transitions(series)
        assertEquals(listOf(1, 3, 5, 7), found.map { it.first })
        assertEquals(
            listOf(
                Rating.EXTREME_FEAR to Rating.FEAR,
                Rating.FEAR to Rating.NEUTRAL,
                Rating.NEUTRAL to Rating.GREED,
                Rating.GREED to Rating.EXTREME_GREED,
            ),
            found.map { it.second },
        )
    }

    @Test fun `区分が動かない系列では何も拾わない`() {
        assertTrue(RatingChange.transitions(listOf(26.0, 30.0, 40.0, 44.0)).isEmpty())
    }
}
