package com.sumideck.fg

/**
 * 表示制御。SPEC.md 6章。
 *
 * 常設はUSゲージ1枚 + 可変1枠の**計2枠**(0章・7章)。枠を増やさない。
 *
 * この層が守る規定:
 * - **完全非表示は手動オフの時だけ**(6.1章)。それ以外の理由で枠が画面から消えてはならない。
 * - **取得失敗・stale・データ欠損でも枠を消さない**(6.4章)。畳んだ1行を残す。
 * - 条件付き折りたたみは **USゲージは既定OFF(常時表示)**、**SL WATCH は既定ON**(6.2章)。
 */
data class DisplaySettings(
    /** 6.1章 手動オン/オフ。これが false のときだけ枠が消える。 */
    val usGaugeVisible: Boolean = true,
    val variableSlotVisible: Boolean = true,

    /**
     * 6.2章 条件付き折りたたみ。**USゲージは既定OFF**。
     * 目的が毎日見て意識づけすることなので、平常時に隠さない。
     */
    val usCollapseEnabled: Boolean = false,

    /** 6.2章 SL WATCH は**既定ON**(割れ0件の日に枠を出す意味がないため)。 */
    val slWatchCollapseEnabled: Boolean = true,

    /** 5.3章 補助線の3段階。既定は水平線のみ。 */
    val lineMode: LineMode = LineMode.HORIZONTAL_ONLY,
)

/** 枠の出し方。 */
enum class SlotDisplay {
    /** 全部出す。 */
    FULL,

    /** 畳む。**数値・区分名・前日差・連続日数は残す**(6.3章)。 */
    COLLAPSED,

    /** 画面から消す。**手動オフの時だけ**到達する(6.1章)。 */
    HIDDEN,
}

object DisplayControl {

    /** 6.2章 SL WATCH の展開条件に使うしきい値。最短距離がこれ未満なら展開する。 */
    const val SL_DISTANCE_THRESHOLD_PCT = 2.0

    /**
     * USゲージの出し方。
     *
     * 判定の順序が規定そのものになっている。
     * 1. 手動オフ → HIDDEN(これ以外で HIDDEN にはならない)
     * 2. 折りたたみ設定がOFF → 常時 FULL
     * 3. データが来ていない → COLLAPSED(**消さない**。6.4章)
     * 4. 6.2章の展開条件に当たる → FULL、当たらなければ COLLAPSED
     */
    fun usGauge(settings: DisplaySettings, state: PanelState): SlotDisplay {
        if (!settings.usGaugeVisible) return SlotDisplay.HIDDEN
        if (!settings.usCollapseEnabled) return SlotDisplay.FULL
        if (state.score == null || state.stale) return SlotDisplay.COLLAPSED
        return if (Collapse.shouldExpandUs(state.score, state.delta, state.streak)) {
            SlotDisplay.FULL
        } else {
            SlotDisplay.COLLAPSED
        }
    }

    /**
     * 可変枠(SL WATCH)の出し方。中身の実装は P4。ここは 6章の規則だけを持つ。
     *
     * @param breaches 割れている建玉の件数。判定できていなければ null。
     * @param minDistancePct SLまでの最短距離(%)。建玉が無ければ null。
     */
    fun variableSlot(
        settings: DisplaySettings,
        breaches: Int?,
        minDistancePct: Double?,
    ): SlotDisplay {
        if (!settings.variableSlotVisible) return SlotDisplay.HIDDEN
        if (!settings.slWatchCollapseEnabled) return SlotDisplay.FULL
        if (breaches == null) return SlotDisplay.COLLAPSED   // データ欠損でも消さない(6.4章)
        if (breaches >= 1) return SlotDisplay.FULL
        if (minDistancePct != null && minDistancePct < SL_DISTANCE_THRESHOLD_PCT) return SlotDisplay.FULL
        return SlotDisplay.COLLAPSED
    }

    /**
     * 常設枠の上限は2(0章・7章)。設定を変えても増えないことを型で示せないので、
     * ここで数えられるようにしておく。
     */
    fun visibleSlotCount(settings: DisplaySettings): Int =
        (if (settings.usGaugeVisible) 1 else 0) + (if (settings.variableSlotVisible) 1 else 0)

    const val MAX_SLOTS = 2
}
