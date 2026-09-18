package com.sumideck.fg.data

import com.sumideck.fg.PanelState
import com.sumideck.fg.RatingChange
import com.sumideck.fg.RatingTransition

/**
 * 区分が変わった日だけの通知。SPEC.md 3章 P5。
 *
 * 「今日は通知すべきか」の判定は [RatingChange](純Kotlin、テスト済み)にある。
 * ここは前回通知した日の記録と、実際の通知の呼び出しだけ。
 *
 * **通知は1日1回まで。** 同じ `as_of` について2度鳴らさない。
 * ランチャー復帰・WorkManager・手動更新のどれから来ても同じ判定を通す。
 *
 * Android 側の実装で必要になるもの:
 * - `POST_NOTIFICATIONS` 権限(API 33+)。**拒否されても枠の表示は続ける**(通知は任意機能)
 * - 通知チャンネル1つ。重要度は既定で十分(音で起こす類の情報ではない)
 * - WorkManager で 07:00 JST 前後に1回(fg.json の取得と同じ契機でよい)
 */
interface NotifiedStateStore {
    suspend fun lastNotifiedAsOf(): String?
    suspend fun setLastNotifiedAsOf(asOf: String)
}

fun interface RatingChangeNotification {
    /** 実際に通知を出す。`title` は区分の遷移、`body` は補足。 */
    fun show(title: String, body: String)
}

class RatingChangeNotifier(
    private val store: NotifiedStateStore,
    private val notification: RatingChangeNotification,
) {
    /**
     * 通知すべきなら出して true を返す。
     * 通知を出せない(権限が無いなど)場合でも、呼び出し側は表示を止めないこと。
     */
    suspend fun notifyIfChanged(state: PanelState): Boolean {
        val transition = RatingChange.detect(state, store.lastNotifiedAsOf()) ?: return false
        notification.show(transition.render(), bodyFor(transition))
        store.setLastNotifiedAsOf(transition.asOf)
        return true
    }

    private fun bodyFor(t: RatingTransition): String =
        if (t.towardFear) "恐怖側に動きました" else "強欲側に動きました"
}
