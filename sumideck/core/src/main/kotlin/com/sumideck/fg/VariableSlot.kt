package com.sumideck.fg

/**
 * 可変枠に何を出すか。SPEC.md 9章。
 *
 * > 常設はUSゲージ1枚。可変枠は**同時に1つだけ**表示する。
 *
 * SL WATCH(9.1章・本命)と EXT(9.2章・任意)は**同じ枠を取り合う**。
 */
enum class VariableSlotContent { SL_WATCH, EXT, NONE }

/**
 * 可変枠に出すもの。
 *
 * @param content 何を出すか
 * @param display どう出すか(FULL / COLLAPSED / HIDDEN)
 * @param fellBackFromExt EXT が使えず SL WATCH に落ちたか(画面に出すかは呼び出し側の判断)
 */
data class VariableSlotPlan(
    val content: VariableSlotContent,
    val display: SlotDisplay,
    val fellBackFromExt: Boolean = false,
)

/**
 * EXT が使えない日に枠をどうするか。
 *
 * **SPEC.md 内で規定が衝突している**(docs/spec_diff.md D16)。
 * - 9.2章: 「取得失敗時は枠ごと非表示にする」
 * - 6.1章: 「完全非表示はこの手動オフの時だけ。それ以外の理由で枠が画面から消えてはならない」
 * - 6.4章: 「取得失敗・stale・データ欠損のときに枠を消すことを禁止する」
 *
 * どちらでも「誤った値は絶対に出さない」(9.2章)は守る。**発注者の判断待ち**。
 */
enum class ExtFailurePolicy {
    /** 6.1章 / 6.4章 に寄せる。枠は残し、数値の代わりに「取得できず」を出す。**既定**。 */
    KEEP_SLOT,

    /** 9.2章の字面に寄せる。EXT が使えない日は枠ごと消す。 */
    HIDE_SLOT,
}

object VariableSlotPlanner {

    /**
     * @param preferred 設定で選んでいる中身(既定は SL WATCH。9.1章が本命のため)
     * @param slBreaches 割れ件数。判定できていなければ null
     * @param extUsable EXT の値が使えるか
     */
    fun plan(
        settings: DisplaySettings,
        preferred: VariableSlotContent,
        slBreaches: Int? = null,
        slMinDistancePct: Double? = null,
        extUsable: Boolean = false,
        extFailurePolicy: ExtFailurePolicy = ExtFailurePolicy.KEEP_SLOT,
    ): VariableSlotPlan {
        // 6.1章: 手動オフが最優先。これ以外で HIDDEN にはしない(HIDE_SLOT を選んだ場合を除く)。
        if (!settings.variableSlotVisible) {
            return VariableSlotPlan(preferred, SlotDisplay.HIDDEN)
        }

        if (preferred == VariableSlotContent.NONE) {
            return VariableSlotPlan(VariableSlotContent.NONE, SlotDisplay.HIDDEN)
        }

        if (preferred == VariableSlotContent.EXT && !extUsable) {
            return when (extFailurePolicy) {
                ExtFailurePolicy.HIDE_SLOT ->
                    VariableSlotPlan(VariableSlotContent.EXT, SlotDisplay.HIDDEN)
                ExtFailurePolicy.KEEP_SLOT ->
                    // 枠は残す。値は出さない。畳んだ1行で「取得できず」を伝える。
                    VariableSlotPlan(VariableSlotContent.EXT, SlotDisplay.COLLAPSED)
            }
        }

        if (preferred == VariableSlotContent.EXT) {
            // EXT は1行で足りるので畳む概念を持たせない。
            return VariableSlotPlan(VariableSlotContent.EXT, SlotDisplay.FULL)
        }

        return VariableSlotPlan(
            VariableSlotContent.SL_WATCH,
            DisplayControl.variableSlot(settings, slBreaches, slMinDistancePct),
        )
    }
}
