package com.sumideck.fg

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `data/ext.json` の読み取り。SPEC.md 9.2章。
 *
 * 寄り前の外部環境を1つだけ。既定は SOX指数の前日比%。
 * engine が取得して置いた**派生統計だけ**が入っている(docs/ext_source.md)。
 */
@Serializable
data class ExtDocument(
    val schema: Int = 0,
    @SerialName("generated_at") val generatedAt: String? = null,
    val ext: ExtBlock? = null,
)

@Serializable
data class ExtBlock(
    val key: String? = null,
    @SerialName("name_ja") val nameJa: String? = null,
    val source: String? = null,
    @SerialName("as_of") val asOf: String? = null,
    @SerialName("change_pct") val changePct: Double? = null,
    val stale: Boolean = true,
)

/** 画面に渡す EXT の状態。 */
data class ExtState(
    val nameJa: String,
    val changePct: Double?,
    val asOfLabel: String?,
    val usable: Boolean,
) {
    /** `SOX −1.5% 09/17` 。使えない日は数値を作らない。 */
    fun render(): String {
        if (!usable || changePct == null) return "$nameJa — 取得できず"
        val date = asOfLabel?.let { " $it" } ?: ""
        return "$nameJa ${SlWatchState.formatPct(changePct)}$date"
    }
}

object ExtBuilder {

    const val DEFAULT_NAME = "SOX"

    /** `ext.json` の本文から状態を作る。読めなければ「使えない」状態を返す。 */
    fun build(
        text: String?,
        nowEpochSeconds: Long? = null,
        staleAfterHours: Long = 36,
    ): ExtState {
        val doc = text?.let { runCatching { FgJson.format.decodeFromString<ExtDocument>(it) }.getOrNull() }
        val block = doc?.ext
        val overdue = PanelBuilder.isOverdue(doc?.generatedAt, nowEpochSeconds, staleAfterHours)
        val usable = block?.changePct != null && !block.stale && !overdue
        return ExtState(
            nameJa = block?.nameJa ?: DEFAULT_NAME,
            // 9.2章「誤った値は絶対に出さない」。使えない日は値そのものを渡さない。
            changePct = if (usable) block?.changePct else null,
            asOfLabel = block?.asOf?.let { PanelBuilder.shortDate(it) },
            usable = usable,
        )
    }
}
