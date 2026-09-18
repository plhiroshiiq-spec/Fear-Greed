package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelStateTest {

    private fun parse(json: String) = FgJson.parseOrNull(json)

    // --- モデル読み込み (5章) ---

    @Test fun `schemaが想定より大きくてもクラッシュしない`() {
        val doc = parse(Fixtures.doc().replace("\"schema\": 2", "\"schema\": 9"))
        assertNotNull(doc)
        assertEquals(9, doc.schema)
        assertEquals(28.54, doc.us?.score)
    }

    @Test fun `未知のキーがあっても落ちない`() {
        val doc = parse(Fixtures.doc(extraTopLevel = """"future_block": {"a": 1}"""))
        assertNotNull(doc)
        assertEquals(28.54, doc.us?.score)
    }

    @Test fun `jpキーが無くても正常に動く`() {
        val doc = parse(Fixtures.doc())
        assertNotNull(doc)
        val state = PanelBuilder.build(doc)
        assertEquals("28", state.scoreText)
    }

    @Test fun `将来jpキーが追加されても落ちない`() {
        val doc = parse(Fixtures.doc(extraTopLevel = """"jp": {"score": 51.2, "rating": "neutral"}"""))
        assertNotNull(doc)
        assertEquals(28.54, doc.us?.score)
    }

    @Test fun `scoreがnullでもクラッシュしない`() {
        // 5.2章の検収。数値は出せないが、状態は組み立てられ枠は消えない。
        val state = PanelBuilder.build(parse(Fixtures.doc(score = null, rating = null)))
        assertNull(state.scoreText)
        assertNull(state.markerPosition)
        assertTrue(!state.hasScore)
    }

    @Test fun `壊れたJSONでもnullを返すだけ`() {
        assertNull(FgJson.parseOrNull("{ this is not json"))
        assertNull(FgJson.parseOrNull(""))
    }

    @Test fun `docがnullでも状態を組み立てられる`() {
        // 機内モードで初回起動し、キャッシュも無い場合。枠は消さず更新遅延を出す(6.4章)。
        val state = PanelBuilder.build(null)
        assertNull(state.scoreText)
        assertTrue(state.stale)
        assertTrue(state.series.isEmpty())
        assertNull(state.scale)
    }

    // --- 推移線 (5.1) ---

    @Test fun `推移線は直近60営業日`() {
        val history = Fixtures.businessSeries(260) { 30.0 + (it % 9) }
        val state = PanelBuilder.build(parse(Fixtures.doc(history = history)))
        assertEquals(60, state.series.size)
        assertEquals(history.takeLast(60).map { it.second }, state.series)
    }

    @Test fun `60本に満たない場合はある分だけ描き埋めない`() {
        val history = Fixtures.businessSeries(18) { 30.0 + it }
        val state = PanelBuilder.build(parse(Fixtures.doc(history = history)))
        assertEquals(18, state.series.size)
        assertEquals(30.0, state.series.first())
        assertTrue(state.series.none { it == 0.0 })   // 0で埋めていない
    }

    @Test fun `履歴が18本未満でも成立する`() {
        for (n in 0..17) {
            val state = PanelBuilder.build(parse(Fixtures.doc(history = Fixtures.businessSeries(n) { 30.0 + it })))
            assertEquals(n, state.series.size, "n=$n")
            if (n == 0) assertNull(state.scale) else assertNotNull(state.scale, "n=$n")
        }
    }

    @Test fun `Y軸は最小値マイナス4と最大値プラス4`() {
        val history = Fixtures.businessSeries(10) { listOf(30.0, 35.0, 28.0, 33.0, 31.0)[it % 5] }
        val state = PanelBuilder.build(parse(Fixtures.doc(history = history)), LineMode.NONE)
        val scale = assertNotNull(state.scale)
        assertEquals(24.0, scale.min, 0.001)   // 28 − 4
        assertEquals(39.0, scale.max, 0.001)   // 35 + 4
        assertEquals(0.0, scale.normalize(24.0), 0.001)
        assertEquals(1.0, scale.normalize(39.0), 0.001)
    }

    @Test fun `全部同じ値でも幅が潰れない`() {
        val state = PanelBuilder.build(parse(Fixtures.doc(history = Fixtures.businessSeries(10) { 30.0 })), LineMode.NONE)
        val scale = assertNotNull(state.scale)
        assertEquals(8.0, scale.span, 0.001)
        assertEquals(0.5, scale.normalize(30.0), 0.001)
    }

    // --- 位置の帯 (5.1) ---

    @Test fun `マーカー位置はスコアに比例する`() {
        assertEquals(0.0, bandPosition(0.0))
        assertEquals(0.5, bandPosition(50.0))
        assertEquals(1.0, bandPosition(100.0))
        assertEquals(1.0, bandPosition(120.0))   // 範囲外は端に丸める
        assertEquals(0.0, bandPosition(-5.0))
    }

    // --- stale / 更新遅延 (5章, 6.4章) ---

    @Test fun `generated_atが36時間より古ければ更新遅延`() {
        val generated = "2026-09-18T06:41:12+09:00"
        val epoch = assertNotNull(IsoTime.toEpochSecondsOrNull(generated))
        val fresh = PanelBuilder.build(parse(Fixtures.doc(generatedAt = generated)), nowEpochSeconds = epoch + 35 * 3600)
        val old = PanelBuilder.build(parse(Fixtures.doc(generatedAt = generated)), nowEpochSeconds = epoch + 37 * 3600)
        assertTrue(!fresh.stale)
        assertTrue(old.stale)
    }

    @Test fun `staleフラグが立っていればそれだけで更新遅延`() {
        val state = PanelBuilder.build(parse(Fixtures.doc(stale = true)))
        assertTrue(state.stale)
    }

    @Test fun `generated_atが読めないときは勝手に古いと言わない`() {
        val state = PanelBuilder.build(parse(Fixtures.doc(generatedAt = "いつか")), nowEpochSeconds = 9_999_999_999)
        assertTrue(!state.stale)
    }

    // --- 折りたたみ (6.3章) ---

    @Test fun `折りたたみ時も数値と区分名と前日差と連続日数が残る`() {
        val state = PanelBuilder.build(parse(Fixtures.doc(score = 29.4, delta = -2.4, streak = -3)))
        val row = CollapsedRow.of(state)
        assertEquals("29", row.scoreText)
        assertEquals("恐怖", row.ratingText)
        assertEquals("−2", row.deltaText)
        assertEquals("↓3日", row.streakText)
        assertEquals("US  29  恐怖  −2  ↓3日", row.render())
    }

    @Test fun `データが来ていない日は数値の代わりに更新遅延と最終取得日`() {
        // 6.4章: 枠を消さず、畳んだ1行を残す。
        val history = Fixtures.businessSeries(5) { 30.0 }
        val state = PanelBuilder.build(parse(Fixtures.doc(stale = true, history = history)))
        val row = CollapsedRow.of(state)
        assertNotNull(row.staleText)
        assertTrue(row.render().startsWith("US  — 更新遅延("))
        assertTrue(row.render().endsWith(")"))
    }

    @Test fun `短い日付の整形`() {
        assertEquals("09/16", PanelBuilder.shortDate("2026-09-16"))
        assertNull(PanelBuilder.shortDate("2026"))
    }

    // --- 条件付き折りたたみ (6.2章。既定はOFF) ---

    @Test fun `展開条件`() {
        assertTrue(Collapse.shouldExpandUs(24.9, 0.0, 0))     // < 25
        assertTrue(Collapse.shouldExpandUs(75.1, 0.0, 0))     // > 75
        assertTrue(Collapse.shouldExpandUs(50.0, -5.0, 0))    // |delta| >= 5
        assertTrue(Collapse.shouldExpandUs(50.0, 0.0, 3))     // |streak| >= 3
        assertTrue(!Collapse.shouldExpandUs(50.0, -4.9, 2))   // 平常時
        assertTrue(!Collapse.shouldExpandUs(null, -9.0, 9))
    }

    // --- 7要素 (5.1章 4段目) ---

    @Test fun `展開時に7要素が渡る`() {
        val state = PanelBuilder.build(parse(Fixtures.doc()))
        assertEquals(7, state.components.size)
        assertTrue(state.components.all { it.nameJa.isNotEmpty() && it.score != null })
        assertEquals(28.69, state.prevClose)
        assertEquals(66.54, state.y1)
    }
}
