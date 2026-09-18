package com.sumideck.fg

/**
 * 取得結果とキャッシュの使い分け。SPEC.md 5章 / 0章の設計原則1。
 *
 * > 通信失敗時はキャッシュを表示し、`generated_at` が36時間以上前 or `stale:true` なら
 * > 「更新遅延」を小さく表示する。
 *
 * Android の実装(DataStore / HTTP)から切り離してあるので、機内モードの挙動を
 * JVM のテストで確かめられる(5.2章の検収「機内モードで起動してもキャッシュ表示される」)。
 */
sealed interface FgLoad {
    /** 取得に成功した。`raw` をキャッシュに書き戻す。 */
    data class Fresh(val doc: FgDocument, val raw: String) : FgLoad

    /** 取得に失敗した(または内容が使えない)のでキャッシュを表示する。 */
    data class Cached(val doc: FgDocument) : FgLoad

    /** 取得もキャッシュも無い。**枠は消さず**「更新遅延」を出す(6.4章)。 */
    data object Unavailable : FgLoad
}

object FgLoader {

    /** SPEC.md 5章: ランチャー復帰時、最終取得から30分以上経過していれば再取得する。 */
    const val REFRESH_INTERVAL_MINUTES = 30L

    /**
     * @param remoteText 今回の取得結果。通信失敗なら null。
     * @param cachedText 前回成功時に保存した本文。無ければ null。
     */
    fun resolve(remoteText: String?, cachedText: String?): FgLoad {
        val fresh = remoteText?.let { text -> FgJson.parseOrNull(text)?.takeIf { it.isUsable() }?.let { it to text } }
        if (fresh != null) return FgLoad.Fresh(fresh.first, fresh.second)

        val cached = cachedText?.let { FgJson.parseOrNull(it) }?.takeIf { it.isUsable() }
        if (cached != null) return FgLoad.Cached(cached)

        return FgLoad.Unavailable
    }

    /**
     * 中身が信用できるか。推測値で埋めるくらいなら前回値を残す(設計原則1)。
     * `score` が無いJSONは「取得できた」とは見なさない。
     */
    fun FgDocument.isUsable(): Boolean = us?.score != null

    fun shouldRefresh(lastFetchEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        if (lastFetchEpochSeconds == null) return true
        return nowEpochSeconds - lastFetchEpochSeconds >= REFRESH_INTERVAL_MINUTES * 60
    }

    /** 表示に渡す [FgDocument]。[FgLoad.Unavailable] は null。 */
    fun FgLoad.document(): FgDocument? = when (this) {
        is FgLoad.Fresh -> doc
        is FgLoad.Cached -> doc
        FgLoad.Unavailable -> null
    }
}
