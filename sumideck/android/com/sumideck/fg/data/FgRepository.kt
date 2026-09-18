package com.sumideck.fg.data

import com.sumideck.fg.FgDocument
import com.sumideck.fg.FgLoad
import com.sumideck.fg.FgLoader
import com.sumideck.fg.FgLoader.document

/**
 * 取得とキャッシュ。SPEC.md 5章。
 *
 * - 取得: リポジトリの `data/fg.json` を raw URL で直接叩く(public なら認証不要)。
 * - キャッシュ: 最後に成功したJSONを DataStore に保持。
 * - 更新: ランチャー復帰時に最終取得から30分以上経過していれば再取得。加えて
 *   WorkManager で 07:00 JST 前後に1回。
 *
 * 判断は [FgLoader](純Kotlin、テスト済み)に寄せ、ここは入出力だけにしてある。
 *
 * **SUMI DECK に取り込むときの注意**
 * - DI は既存の流儀に合わせること(5章)。ここでは素のコンストラクタ注入にしてある。
 * - private リポジトリにする場合は GitHub Contents API + 読み取り専用 fine-grained PAT を
 *   `local.properties` → `BuildConfig` で注入する。**PATはリポジトリにコミットしない。**
 */
class FgRepository(
    private val remote: FgRemoteSource,
    private val cache: FgCache,
    private val nowEpochSeconds: () -> Long,
) {
    /**
     * @param force ランチャー復帰時の30分判定を飛ばす(手動更新など)。
     */
    suspend fun load(force: Boolean = false): FgLoad {
        val cached = cache.read()
        if (!force && !FgLoader.shouldRefresh(cache.lastFetchEpochSeconds(), nowEpochSeconds())) {
            // まだ取りに行かなくてよい。キャッシュをそのまま返す。
            return FgLoader.resolve(remoteText = null, cachedText = cached)
        }

        val fetched = runCatching { remote.fetch() }.getOrNull()
        val load = FgLoader.resolve(remoteText = fetched, cachedText = cached)
        if (load is FgLoad.Fresh) cache.write(load.raw, nowEpochSeconds())
        return load
    }

    suspend fun currentDocument(force: Boolean = false): FgDocument? = load(force).document()
}

/** `data/fg.json` の本文を返す。通信失敗時は null を返すか例外を投げてよい(どちらも扱う)。 */
fun interface FgRemoteSource {
    suspend fun fetch(): String?
}

/** 最後に成功したJSONと、その取得時刻。DataStore で実装する。 */
interface FgCache {
    suspend fun read(): String?
    suspend fun write(raw: String, fetchedAtEpochSeconds: Long)
    suspend fun lastFetchEpochSeconds(): Long?
}
