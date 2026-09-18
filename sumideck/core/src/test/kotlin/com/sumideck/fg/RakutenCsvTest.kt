package com.sumideck.fg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RakutenCsvTest {

    @Test fun `ヘッダが一致すれば読める`() {
        val csv = """
            約定日,銘柄コード,銘柄名,数量,実現損益
            2026/09/17,7203,トヨタ自動車,100,12345
            2026/09/17,6758,ソニーグループ,50,-4321
        """.trimIndent()
        val result = RakutenRealizedPnlCsv.parse(csv)
        assertIs<CsvParseResult.Success>(result)
        assertEquals(
            listOf(ClosedLot("7203", "2026-09-17", 100), ClosedLot("6758", "2026-09-17", 50)),
            result.lots,
        )
    }

    @Test fun `先頭に説明行があっても列名の行を探す`() {
        val csv = """
            実現損益明細
            出力日時: 2026/09/18 06:00
            約定日,銘柄コード,数量
            2026/09/17,7203,100
        """.trimIndent()
        val result = RakutenRealizedPnlCsv.parse(csv)
        assertIs<CsvParseResult.Success>(result)
        assertEquals(1, result.lots.size)
    }

    @Test fun `ヘッダが分からなければ見えたヘッダを返す`() {
        // 推測で列位置を決め打ちしない。何が見えたかを報告して発注者に確認する。
        val csv = "Date,Symbol,Qty\n2026/09/17,7203,100"
        val result = RakutenRealizedPnlCsv.parse(csv)
        assertIs<CsvParseResult.UnknownFormat>(result)
        assertEquals(listOf("Date", "Symbol", "Qty"), result.headers)
        assertTrue(result.missing.contains("銘柄コード"))
    }

    @Test fun `引用符とカンマ区切りの数量を読む`() {
        val csv = """"約定日","銘柄コード","数量"
"2026/09/17","7203","1,500""""
        val result = RakutenRealizedPnlCsv.parse(csv)
        assertIs<CsvParseResult.Success>(result)
        assertEquals(1500, result.lots.single().shares)
    }

    @Test fun `Shift_JISのバイト列を読む`() {
        val text = "約定日,銘柄コード,数量\n2026/09/17,7203,100"
        val bytes = text.toByteArray(charset("windows-31j"))
        val result = RakutenRealizedPnlCsv.parse(bytes)
        assertIs<CsvParseResult.Success>(result)
        assertEquals("7203", result.lots.single().code)
    }

    @Test fun `日付の表記ゆれを吸収する`() {
        assertEquals("2026-09-17", RakutenRealizedPnlCsv.normalizeDate("2026/09/17"))
        assertEquals("2026-09-17", RakutenRealizedPnlCsv.normalizeDate("2026-9-17"))
        assertEquals("2026-09-17", RakutenRealizedPnlCsv.normalizeDate("2026年9月17日"))
        assertEquals("なにか", RakutenRealizedPnlCsv.normalizeDate("なにか"))
    }

    @Test fun `空のCSV`() {
        assertEquals(CsvParseResult.Empty, RakutenRealizedPnlCsv.parse(""))
        assertEquals(CsvParseResult.Empty, RakutenRealizedPnlCsv.parse("約定日,銘柄コード,数量"))
    }

    // --- 消し込み ---

    @Test fun `全株決済された建玉だけ消える`() {
        val positions = listOf(
            Position("7203", 2500.0, 2400.0, 100, "2026-09-01"),
            Position("6758", 3000.0, 2900.0, 50, "2026-09-01"),
        )
        val remaining = RakutenRealizedPnlCsv.applyCloses(
            positions, listOf(ClosedLot("7203", "2026-09-17", 100)),
        )
        assertEquals(listOf("6758"), remaining.map { it.code })
    }

    @Test fun `一部決済は株数を減らすだけで消さない`() {
        // 勝手に消さない(設計原則1)。
        val positions = listOf(Position("7203", 2500.0, 2400.0, 100, "2026-09-01"))
        val remaining = RakutenRealizedPnlCsv.applyCloses(
            positions, listOf(ClosedLot("7203", "2026-09-17", 30)),
        )
        assertEquals(1, remaining.size)
        assertEquals(70, remaining.single().shares)
    }

    @Test fun `CSVに無い銘柄は触らない`() {
        val positions = listOf(Position("9984", 8000.0, 7500.0, 10, "2026-09-01"))
        assertEquals(positions, RakutenRealizedPnlCsv.applyCloses(positions, emptyList()))
    }

    @Test fun `同じ銘柄の決済は合算する`() {
        val positions = listOf(Position("7203", 2500.0, 2400.0, 100, "2026-09-01"))
        val remaining = RakutenRealizedPnlCsv.applyCloses(
            positions,
            listOf(ClosedLot("7203", "2026-09-16", 40), ClosedLot("7203", "2026-09-17", 60)),
        )
        assertTrue(remaining.isEmpty())
    }
}
