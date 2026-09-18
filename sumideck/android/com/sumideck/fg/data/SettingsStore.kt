package com.sumideck.fg.data

import com.sumideck.fg.DisplaySettings
import com.sumideck.fg.LineMode

/**
 * 表示設定の保存先。SPEC.md 6.1章「DataStore に保存」。
 *
 * 判定そのものは [com.sumideck.fg.DisplayControl](純Kotlin、テスト済み)にある。
 * ここは読み書きだけ。SUMI DECK 側に既存の設定ストアがあればそちらに寄せること(5章)。
 *
 * DataStore(Preferences)での実装例:
 * ```kotlin
 * private val KEY_US_VISIBLE = booleanPreferencesKey("fg_us_visible")
 * override val settings: Flow<DisplaySettings> = dataStore.data.map { p ->
 *     DisplaySettings(usGaugeVisible = p[KEY_US_VISIBLE] ?: true, ...)
 * }
 * ```
 */
interface SettingsStore {
    suspend fun read(): DisplaySettings
    suspend fun setUsGaugeVisible(visible: Boolean)
    suspend fun setVariableSlotVisible(visible: Boolean)
    suspend fun setUsCollapseEnabled(enabled: Boolean)
    suspend fun setSlWatchCollapseEnabled(enabled: Boolean)
    suspend fun setLineMode(mode: LineMode)
}

/** テストと初回起動用。SPEC.md の既定値をそのまま持つ。 */
class InMemorySettingsStore(initial: DisplaySettings = DisplaySettings()) : SettingsStore {
    private var current = initial
    override suspend fun read(): DisplaySettings = current
    override suspend fun setUsGaugeVisible(visible: Boolean) { current = current.copy(usGaugeVisible = visible) }
    override suspend fun setVariableSlotVisible(visible: Boolean) { current = current.copy(variableSlotVisible = visible) }
    override suspend fun setUsCollapseEnabled(enabled: Boolean) { current = current.copy(usCollapseEnabled = enabled) }
    override suspend fun setSlWatchCollapseEnabled(enabled: Boolean) { current = current.copy(slWatchCollapseEnabled = enabled) }
    override suspend fun setLineMode(mode: LineMode) { current = current.copy(lineMode = mode) }
}
