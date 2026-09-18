package com.sumideck.fg.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * public リポジトリの raw URL をそのまま叩く。認証不要なので0円方針と相性が良い(SPEC.md 5章)。
 *
 * CNN のデータを再配布しない配慮として、リポジトリにはスコアのみが置かれ生JSONは無い。
 *
 * OkHttp など既存の HTTP クライアントが SUMI DECK にあるなら、そちらで置き換えること(5章)。
 */
class HttpFgRemoteSource(
    private val url: String = DEFAULT_URL,
    private val timeoutMillis: Int = 10_000,
) : FgRemoteSource {

    override suspend fun fetch(): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null   // 通信失敗はキャッシュ表示で吸収する(SPEC.md 5章)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val RAW_BASE =
            "https://raw.githubusercontent.com/plhiroshiiq-spec/Fear-Greed/main/data"

        const val DEFAULT_URL = "$RAW_BASE/fg.json"

        /** EXT(SOX指数の前日比%。SPEC.md 9.2章)。engine が置いた派生統計だけが入っている。 */
        const val EXT_URL = "$RAW_BASE/ext.json"
    }
}
