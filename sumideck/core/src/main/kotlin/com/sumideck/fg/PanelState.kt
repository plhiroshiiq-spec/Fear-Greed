package com.sumideck.fg

/**
 * 画面に渡す描画状態。SPEC.md 5.1章 / 5.3章 / 6.3章。
 *
 * Compose 側はここに入っている値をそのまま描くだけにする。計算はすべてこの層で終える
 * (再コンポーズのたびに計算が走らないようにするため。5.2章の検収)。
 */
data class PanelState(
    val scoreText: String?,          // 整数表示。取得できていなければ null
    val rating: Rating?,
    val deltaText: String?,
    val streakText: String?,
    val markerPosition: Double?,     // 位置の帯の 0..1。score が無ければ null
    val series: List<Double>,        // 推移線(古い順、最大60本)
    val scale: ChartScale?,          // series が空なら null
    val overlay: Overlay,
    val components: List<Component>,
    val prevClose: Double?,
    val w1: Double?,
    val m1: Double?,
    val y1: Double?,
    val stale: Boolean,
    val lastUpdatedLabel: String?,   // stale のときに出す `09/16` 形式
) {
    /** 数値が出せない状態か。枠は消さず「— 更新遅延」を出す(6.4章)。 */
    val hasScore: Boolean get() = scoreText != null
}

object PanelBuilder {
    /** SPEC.md 5.1章: 直近60営業日。 */
    const val WINDOW = 60

    fun build(
        doc: FgDocument?,
        mode: LineMode = LineMode.HORIZONTAL_ONLY,
        nowEpochSeconds: Long? = null,
        staleAfterHours: Long = 36,
    ): PanelState {
        val us = doc?.us
        val score = us?.score
        val points = us?.historyPoints().orEmpty()
        // 足りない分を0や前値で埋めない(5.1章)。ある分だけ使う。
        val series = points.takeLast(WINDOW).map { it.score }

        val overlay = Levels.build(series, mode)
        val scale = ChartScale.of(series, overlay.scaleValues(series.size))

        val stale = (us?.stale ?: true) || isOverdue(doc?.generatedAt, nowEpochSeconds, staleAfterHours)

        return PanelState(
            scoreText = score?.let { Display.toInt(it).toString() },
            rating = Rating.resolve(us?.rating, score),
            deltaText = Display.deltaLabel(us?.delta),
            streakText = Display.streakLabel(us?.streak ?: 0),
            markerPosition = score?.let { bandPosition(it) },
            series = series,
            scale = scale,
            overlay = overlay,
            components = us?.components.orEmpty(),
            prevClose = us?.prevClose,
            w1 = us?.w1,
            m1 = us?.m1,
            y1 = us?.y1,
            stale = stale,
            lastUpdatedLabel = points.lastOrNull()?.date?.let { shortDate(it) }
                ?: us?.asOf?.let { shortDate(it) },
        )
    }

    /** `2026-09-16` → `09/16`。読めなければ null。 */
    internal fun shortDate(iso: String): String? {
        val parts = iso.trim().take(10).split("-")
        if (parts.size != 3) return null
        return "${parts[1]}/${parts[2]}"
    }

    /** `generated_at` が staleAfterHours より古いか。判定できないときは false(勝手に古いと言わない)。 */
    internal fun isOverdue(generatedAt: String?, nowEpochSeconds: Long?, hours: Long): Boolean {
        if (generatedAt == null || nowEpochSeconds == null) return false
        val epoch = IsoTime.toEpochSecondsOrNull(generatedAt) ?: return false
        return nowEpochSeconds - epoch > hours * 3600
    }
}

/**
 * 折りたたみ時の1行。SPEC.md 6.3章。
 *
 * > **折りたたみ = バーだけにする、ではない。数値は常に残す。**
 *
 * 畳んでも 名前 / 数値 / 区分名 / 前日差 / 連続日数 は必ず残す。
 * データが来ていないときは数値の代わりに `— 更新遅延(09/16)` を出し、**枠は消さない**(6.4章)。
 */
data class CollapsedRow(
    val label: String,
    val scoreText: String?,
    val ratingText: String?,
    val deltaText: String?,
    val streakText: String?,
    val staleText: String?,
) {
    /** `US  29  恐怖   −2 ↓3日` / `US  — 更新遅延(09/16)` */
    fun render(): String = buildList {
        add(label)
        if (staleText != null) add(staleText)
        else {
            scoreText?.let { add(it) }
            ratingText?.let { add(it) }
            deltaText?.let { add(it) }
            streakText?.let { add(it) }
        }
    }.joinToString("  ")

    companion object {
        fun of(state: PanelState, label: String = "US"): CollapsedRow {
            val unavailable = state.scoreText == null || state.stale
            return CollapsedRow(
                label = label,
                scoreText = state.scoreText,
                ratingText = state.rating?.ja,
                deltaText = state.deltaText,
                streakText = state.streakText,
                staleText = if (unavailable) {
                    val date = state.lastUpdatedLabel
                    if (date != null) "— 更新遅延($date)" else "— 更新遅延"
                } else null,
            )
        }
    }
}

/**
 * 条件付き折りたたみの判定。SPEC.md 6.2章。**既定はOFF(常時表示)**。
 * ONにしたときだけこの判定を使う。
 */
object Collapse {
    fun shouldExpandUs(score: Double?, delta: Double?, streak: Int): Boolean {
        if (score == null) return false
        if (score < 25.0 || score > 75.0) return true
        if (delta != null && kotlin.math.abs(delta) >= 5.0) return true
        return kotlin.math.abs(streak) >= 3
    }
}
