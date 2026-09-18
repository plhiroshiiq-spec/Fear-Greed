package com.sumideck.fg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * data/fg.json (schema 2) の読み取りモデル。SPEC.md 2章 / 5章。
 *
 * 5章の要求:
 * - `ignoreUnknownKeys = true`
 * - `schema` が想定より大きくてもクラッシュしない
 * - `jp` キーが **無い**ことを前提にする(将来追加されても落ちない)
 *
 * すべてのフィールドを nullable にしてあるのは、欠損でクラッシュさせないため
 * (5.2章の検収「score が null でもクラッシュしない」)。
 */
@Serializable
data class FgDocument(
    val schema: Int = 0,
    @SerialName("generated_at") val generatedAt: String? = null,
    val us: UsBlock? = null,
)

@Serializable
data class UsBlock(
    val source: String? = null,
    @SerialName("as_of") val asOf: String? = null,
    val score: Double? = null,
    val rating: String? = null,
    @SerialName("prev_close") val prevClose: Double? = null,
    val w1: Double? = null,
    val m1: Double? = null,
    val y1: Double? = null,
    val delta: Double? = null,
    val streak: Int = 0,
    val components: List<Component> = emptyList(),
    /** [["2026-09-18", 28.54], ...] 古い順。日付でdedupe済み。 */
    val history: List<List<HistoryCell>> = emptyList(),
    val stale: Boolean = false,
)

@Serializable
data class Component(
    val key: String = "",
    @SerialName("name_ja") val nameJa: String = "",
    val score: Double? = null,
    val raw: Double? = null,
)

/** history の要素は文字列(日付)と数値(スコア)が混ざるため、型を吸収する。 */
@Serializable(with = HistoryCellSerializer::class)
data class HistoryCell(val text: String?, val number: Double?)

/** 描画に渡す1点。 */
data class HistoryPoint(val date: String, val score: Double)

object FgJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 壊れたJSONで落ちない。読めなければ null を返し、呼び出し側はキャッシュを使う。 */
    fun parseOrNull(text: String): FgDocument? = runCatching { format.decodeFromString<FgDocument>(text) }.getOrNull()
}

/** history を (日付, スコア) の並びに直す。壊れた行は捨てる。 */
fun UsBlock.historyPoints(): List<HistoryPoint> =
    history.mapNotNull { cells ->
        val date = cells.getOrNull(0)?.text
        val score = cells.getOrNull(1)?.number
        if (date != null && score != null) HistoryPoint(date, score) else null
    }
