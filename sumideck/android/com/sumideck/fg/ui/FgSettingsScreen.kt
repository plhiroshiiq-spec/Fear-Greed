package com.sumideck.fg.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sumideck.fg.DisplaySettings
import com.sumideck.fg.LineMode

/**
 * 設定画面。SPEC.md 6.1章 / 6.2章 / 5.3章。
 *
 * - **手動オン/オフ が必須・最優先**(6.1章)。USゲージ と 可変枠 を個別に切り替える。
 * - 条件付き折りたたみは**選べるようにするだけ**。USゲージの既定はOFF(常時表示)。
 * - 補助線は3段階(5.3章)。既定は水平線のみ。
 *
 * Material の Switch / RadioButton を使うと既存テーマと衝突しやすいので、
 * ここでは素の行だけを置いてある。SUMI DECK の既存部品に差し替えること(5章)。
 */
@Composable
fun FgSettingsScreen(
    settings: DisplaySettings,
    onUsGaugeVisible: (Boolean) -> Unit,
    onVariableSlotVisible: (Boolean) -> Unit,
    onUsCollapseEnabled: (Boolean) -> Unit,
    onSlWatchCollapseEnabled: (Boolean) -> Unit,
    onLineMode: (LineMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        SectionTitle("枠の表示")
        ToggleRow("USゲージ", settings.usGaugeVisible, onUsGaugeVisible)
        ToggleRow("可変枠", settings.variableSlotVisible, onVariableSlotVisible)
        Caption("オフにした枠だけが画面から消えます。取得に失敗した日も枠は残ります。")

        Spacer(Modifier.height(20.dp))
        SectionTitle("条件付き折りたたみ")
        ToggleRow("USゲージを平常時に畳む", settings.usCollapseEnabled, onUsCollapseEnabled)
        ToggleRow("SL WATCH を平常時に畳む", settings.slWatchCollapseEnabled, onSlWatchCollapseEnabled)
        Caption("畳んでも数値・区分・前日差・連続日数は残ります。")

        Spacer(Modifier.height(20.dp))
        SectionTitle("推移線の補助線")
        LineMode.entries.forEach { mode ->
            ChoiceRow(lineModeLabel(mode), settings.lineMode == mode) { onLineMode(mode) }
        }
    }
}

private fun lineModeLabel(mode: LineMode): String = when (mode) {
    LineMode.HORIZONTAL_ONLY -> "水平線のみ(既定)"
    LineMode.HORIZONTAL_AND_DIAGONAL -> "水平線 + 斜線"
    LineMode.NONE -> "線なし"
}

@Composable
private fun SectionTitle(text: String) {
    FgText(text, FgDimens.ratingSize, FgColors.foreground)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Caption(text: String) {
    Spacer(Modifier.height(6.dp))
    FgText(text, FgDimens.captionSize, FgColors.textMuted)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        FgText(label, FgDimens.deltaSize, FgColors.foreground)
        FgText(if (checked) "オン" else "オフ", FgDimens.deltaSize, FgColors.textSecondary)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FgText(if (selected) "●" else "○", FgDimens.deltaSize, FgColors.textSecondary)
        Spacer(Modifier.width(10.dp))
        FgText(label, FgDimens.deltaSize, FgColors.foreground)
    }
}
