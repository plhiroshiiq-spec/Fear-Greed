package com.sumideck.fg

/**
 * 区分が変わった日だけの通知。SPEC.md 3章 P5。
 *
 * > P5(任意)EXT / 通知 … 外部環境の表示(9.2)、**区分が変わった日だけの通知**。
 *
 * 通知の出し方(権限・チャンネル・WorkManager)は Android 側の仕事。
 * ここは「今日は通知すべきか」だけを決める。判定を純Kotlinに置けばテストできる。
 *
 * ## 出さない条件
 *
 * - 区分が前営業日と同じ日は出さない(**変わった日だけ**)
 * - `stale` の日は出さない(値が信用できない。設計原則1)
 * - 同じ `as_of` について2回出さない(再取得や再起動で二重に鳴らさない)
 * - 履歴が2本未満で前営業日の区分が分からない日は出さない
 */
data class RatingTransition(
    val from: Rating,
    val to: Rating,
    val asOf: String,
    val score: Double,
) {
    /** `恐怖 → 中立 45`。通知の1行。 */
    fun render(): String = "${from.ja} → ${to.ja} ${Display.toInt(score)}"

    /** 悪化方向(Greed側からFear側へ)か。通知の文面を変えたい場合に使う。 */
    val towardFear: Boolean get() = to.ordinal < from.ordinal
}

object RatingChange {

    /**
     * 通知すべき区分変化を返す。無ければ null。
     *
     * @param lastNotifiedAsOf 前回通知した `as_of`。同じ日には2度出さない。
     */
    fun detect(state: PanelState, lastNotifiedAsOf: String? = null): RatingTransition? {
        if (state.stale) return null
        val score = state.score ?: return null
        val asOf = state.asOf ?: return null
        if (state.series.size < 2) return null
        if (lastNotifiedAsOf == asOf) return null   // 同じ日に2度鳴らさない

        val today = Rating.resolve(null, score) ?: return null
        val yesterday = Rating.fromScore(state.series[state.series.lastIndex - 1])
        if (today == yesterday) return null

        return RatingTransition(from = yesterday, to = today, asOf = asOf, score = score)
    }

    /**
     * 履歴から区分が変わった日を全部拾う。過去の検証用。
     * @return (インデックス, 遷移) の並び
     */
    fun transitions(series: List<Double>): List<Pair<Int, Pair<Rating, Rating>>> =
        (1 until series.size).mapNotNull { i ->
            val from = Rating.fromScore(series[i - 1])
            val to = Rating.fromScore(series[i])
            if (from == to) null else i to (from to to)
        }
}
