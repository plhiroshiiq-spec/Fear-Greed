package com.sumideck.fg

import java.nio.charset.Charset

/**
 * 楽天証券の実現損益CSV(Shift-JIS)の取り込み。SPEC.md 9.1章。
 *
 * > 決済の消し込み: 楽天証券の実現損益CSV(Shift-JIS)を取り込めるなら自動クローズ。
 * > 取り込めない場合は手動クローズ。
 *
 * ## ⚠️ ヘッダ名は実ファイルで未確認
 *
 * 実物のCSVをこの環境から入手できなかったため、列名は**候補名との一致で探す**方式にしてある。
 * 一致しなければ [CsvParseResult.UnknownFormat] を返し、**見えたヘッダをそのまま報告する**。
 * 推測で列位置を決め打ちして、違う列を数量として読むより安全なため。
 *
 * 実ファイルが手に入ったら、そのヘッダを [CODE_HEADERS] などに足すだけで通るようになる。
 * 手動クローズの経路は残すこと(9.1章)。
 */
data class ClosedLot(val code: String, val date: String, val shares: Int)

sealed interface CsvParseResult {
    data class Success(val lots: List<ClosedLot>) : CsvParseResult

    /** 列が見つからなかった。[headers] を発注者に見せて候補名を足す。 */
    data class UnknownFormat(val headers: List<String>, val missing: List<String>) : CsvParseResult

    /** 中身が空、または行が1つも読めなかった。 */
    data object Empty : CsvParseResult
}

object RakutenRealizedPnlCsv {

    val CODE_HEADERS = listOf("銘柄コード", "コード", "銘柄", "ティッカー")
    val DATE_HEADERS = listOf("約定日", "受渡日", "決済日", "売却日", "取引日")
    val SHARES_HEADERS = listOf("数量", "約定数量", "株数", "数量[株]", "約定数量[株]")

    /** Shift-JIS のバイト列を読む。Android でも JVM でも通る名前を順に試す。 */
    fun decode(bytes: ByteArray): String {
        for (name in listOf("windows-31j", "Shift_JIS", "MS932")) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            return String(bytes, charset)
        }
        return String(bytes)
    }

    fun parse(bytes: ByteArray): CsvParseResult = parse(decode(bytes))

    fun parse(text: String): CsvParseResult {
        val lines = text.lineSequence()
            .map { it.trim().removePrefix("﻿") }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return CsvParseResult.Empty

        // 楽天のCSVは先頭に説明行が入ることがあるので、列名が揃う行をヘッダとして探す。
        val headerIndex = lines.indices.firstOrNull { i ->
            val cells = splitCsvLine(lines[i])
            findColumn(cells, CODE_HEADERS) != null && findColumn(cells, SHARES_HEADERS) != null
        }
        if (headerIndex == null) {
            val cells = splitCsvLine(lines.first())
            return CsvParseResult.UnknownFormat(
                headers = cells,
                missing = listOfNotNull(
                    "銘柄コード".takeIf { findColumn(cells, CODE_HEADERS) == null },
                    "数量".takeIf { findColumn(cells, SHARES_HEADERS) == null },
                ),
            )
        }

        val header = splitCsvLine(lines[headerIndex])
        val codeAt = findColumn(header, CODE_HEADERS)!!
        val sharesAt = findColumn(header, SHARES_HEADERS)!!
        val dateAt = findColumn(header, DATE_HEADERS)

        val lots = lines.drop(headerIndex + 1).mapNotNull { line ->
            val cells = splitCsvLine(line)
            val code = cells.getOrNull(codeAt)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val shares = cells.getOrNull(sharesAt)?.let { parseInt(it) } ?: return@mapNotNull null
            val date = dateAt?.let { cells.getOrNull(it) }?.let { normalizeDate(it) } ?: ""
            ClosedLot(code, date, shares)
        }
        return if (lots.isEmpty()) CsvParseResult.Empty else CsvParseResult.Success(lots)
    }

    /** 同じ銘柄の決済を合算し、建玉から引く株数を返す。 */
    fun closedSharesByCode(lots: List<ClosedLot>): Map<String, Int> =
        lots.groupBy { it.code }.mapValues { (_, rows) -> rows.sumOf { it.shares } }

    /**
     * 決済ぶんを建玉から引く。**全株決済された建玉だけを消す。**
     * 一部決済は株数を減らすだけにして、勝手に消さない(設計原則1)。
     */
    fun applyCloses(positions: List<Position>, lots: List<ClosedLot>): List<Position> {
        val closed = closedSharesByCode(lots).toMutableMap()
        return positions.mapNotNull { p ->
            val available = closed[p.code] ?: return@mapNotNull p
            val used = minOf(available, p.shares)
            closed[p.code] = available - used
            val remaining = p.shares - used
            if (remaining <= 0) null else p.copy(shares = remaining)
        }
    }

    // --- 小物 ---

    internal fun findColumn(cells: List<String>, candidates: List<String>): Int? =
        cells.indexOfFirst { cell ->
            val normalized = cell.trim().trim('"')
            candidates.any { normalized == it || normalized.startsWith(it) }
        }.takeIf { it >= 0 }

    /** `"a","b"` と `a,b` の両方を読む最小のCSV分割。改行入りセルは想定しない。 */
    internal fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out.map { it.trim() }
    }

    internal fun parseInt(raw: String): Int? =
        raw.trim().trim('"').replace(",", "").replace("株", "").trim().toIntOrNull()

    /** `2026/09/17` `2026-09-17` `2026年9月17日` を `2026-09-17` に寄せる。読めなければ元のまま。 */
    internal fun normalizeDate(raw: String): String {
        val digits = Regex("""(\d{4})\D+(\d{1,2})\D+(\d{1,2})""").find(raw.trim().trim('"'))
            ?: return raw.trim().trim('"')
        val (y, m, d) = digits.destructured
        return "%04d-%02d-%02d".format(y.toInt(), m.toInt(), d.toInt())
    }
}
