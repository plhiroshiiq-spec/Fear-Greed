package com.sumideck.fg.data

import com.sumideck.fg.ClosedLot
import com.sumideck.fg.DailyClose
import com.sumideck.fg.Position
import com.sumideck.fg.RakutenRealizedPnlCsv

/**
 * 建玉と終値の保存先。SPEC.md 9.1章。
 *
 * > 保存先: `positions.json`(**端末内 DataStore のみ**。リポジトリには置かない)。
 *
 * このインターフェースの実装は DataStore だけにすること。ネットワークに出す実装を足さない。
 * 建玉は発注者の資産情報そのものなので、端末の外に出す理由が無い。
 */
interface PositionStore {
    suspend fun positions(): List<Position>
    suspend fun add(position: Position)
    suspend fun update(position: Position)
    suspend fun remove(code: String)

    /** 手入力した終値(docs/price_source.md の (a))。 */
    suspend fun closes(): List<DailyClose>
    suspend fun putClose(close: DailyClose)
}

/**
 * 決済の消し込み。SPEC.md 9.1章。
 *
 * 楽天証券の実現損益CSVを取り込めた場合だけ自動クローズする。
 * **ヘッダが読めなければ何も消さず、見えたヘッダを返す**([CloseImportResult.NeedsMapping])。
 * 手動クローズの経路は必ず残すこと。
 */
sealed interface CloseImportResult {
    data class Applied(val closed: List<ClosedLot>, val remaining: List<Position>) : CloseImportResult
    data class NeedsMapping(val headers: List<String>, val missing: List<String>) : CloseImportResult
    data object NothingToDo : CloseImportResult
}

class PositionCloser(private val store: PositionStore) {

    suspend fun importRakutenCsv(bytes: ByteArray): CloseImportResult {
        return when (val parsed = RakutenRealizedPnlCsv.parse(bytes)) {
            is com.sumideck.fg.CsvParseResult.UnknownFormat ->
                CloseImportResult.NeedsMapping(parsed.headers, parsed.missing)

            com.sumideck.fg.CsvParseResult.Empty -> CloseImportResult.NothingToDo

            is com.sumideck.fg.CsvParseResult.Success -> {
                val before = store.positions()
                val after = RakutenRealizedPnlCsv.applyCloses(before, parsed.lots)
                // 消えた建玉と株数が減った建玉を反映する。
                val afterByCode = after.associateBy { it.code }
                before.forEach { p ->
                    val updated = afterByCode[p.code]
                    when {
                        updated == null -> store.remove(p.code)
                        updated.shares != p.shares -> store.update(updated)
                    }
                }
                CloseImportResult.Applied(parsed.lots, after)
            }
        }
    }
}
