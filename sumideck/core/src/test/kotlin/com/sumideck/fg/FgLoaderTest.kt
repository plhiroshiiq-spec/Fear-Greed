package com.sumideck.fg

import com.sumideck.fg.FgLoader.document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FgLoaderTest {

    private val good = Fixtures.doc(score = 28.54)
    private val older = Fixtures.doc(score = 61.2, rating = "greed")

    @Test fun `取得に成功したらそれを使う`() {
        val load = FgLoader.resolve(remoteText = good, cachedText = older)
        assertIs<FgLoad.Fresh>(load)
        assertEquals(28.54, load.doc.us?.score)
        assertEquals(good, load.raw)
    }

    @Test fun `機内モードでもキャッシュを表示する`() {
        // 5.2章の検収。通信結果が null でも、キャッシュがあれば数値が出る。
        val load = FgLoader.resolve(remoteText = null, cachedText = older)
        assertIs<FgLoad.Cached>(load)
        val state = PanelBuilder.build(load.document())
        assertEquals("61", state.scoreText)
        assertEquals(Rating.GREED, state.rating)
    }

    @Test fun `取得できた本文が壊れていればキャッシュに落とす`() {
        val load = FgLoader.resolve(remoteText = "{ broken", cachedText = older)
        assertIs<FgLoad.Cached>(load)
        assertEquals(61.2, load.doc.us?.score)
    }

    @Test fun `scoreが無い本文は取得成功と見なさない`() {
        // 推測値で埋めるくらいなら前回値を残す(設計原則1)。
        val load = FgLoader.resolve(remoteText = Fixtures.doc(score = null), cachedText = older)
        assertIs<FgLoad.Cached>(load)
        assertEquals(61.2, load.doc.us?.score)
    }

    @Test fun `取得もキャッシュも無ければUnavailable`() {
        val load = FgLoader.resolve(remoteText = null, cachedText = null)
        assertEquals(FgLoad.Unavailable, load)
        assertNull(load.document())
        // 6.4章: それでも枠は消さない。更新遅延の1行が出る。
        val row = CollapsedRow.of(PanelBuilder.build(load.document()))
        assertNotNull(row.staleText)
        assertTrue(row.render().startsWith("US"))
    }

    @Test fun `キャッシュが壊れていてもクラッシュしない`() {
        assertEquals(FgLoad.Unavailable, FgLoader.resolve(null, "not json at all"))
    }

    @Test fun `30分経っていなければ再取得しない`() {
        val now = 1_800_000_000L
        assertTrue(!FgLoader.shouldRefresh(now - 29 * 60, now))
        assertTrue(FgLoader.shouldRefresh(now - 30 * 60, now))
        assertTrue(FgLoader.shouldRefresh(null, now))
    }
}
