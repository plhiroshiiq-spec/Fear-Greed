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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.sumideck.fg.DailyClose
import com.sumideck.fg.Position
import com.sumideck.fg.SlRule

/**
 * 建玉の追加フォーム。SPEC.md 9.1章。
 *
 * > 入力(手動): `code` / `entry`(建値) / `sl` / `shares` / `opened_at`。
 * > アプリ内フォームから追加(1件20秒程度)。
 *
 * `sl_rule` の既定は `NONE`。**SLを自動で下げる選択肢は用意しない**(9.1章)。
 */
@Composable
fun PositionForm(
    onAdd: (Position) -> Unit,
    modifier: Modifier = Modifier,
    today: String = "",
) {
    var code by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var sl by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf("") }
    var openedAt by remember { mutableStateOf(today) }
    var rule by remember { mutableStateOf(SlRule.NONE) }

    val position = buildPosition(code, entry, sl, shares, openedAt, rule)

    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        FieldRow("銘柄コード", code, KeyboardType.Number) { code = it }
        FieldRow("建値", entry, KeyboardType.Decimal) { entry = it }
        FieldRow("SL", sl, KeyboardType.Decimal) { sl = it }
        FieldRow("株数", shares, KeyboardType.Number) { shares = it }
        FieldRow("建てた日", openedAt, KeyboardType.Ascii) { openedAt = it }

        Spacer(Modifier.height(12.dp))
        FgText("SLの自動更新", FgDimens.captionSize, FgColors.textMuted)
        SlRule.entries.forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { rule = r },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FgText(if (rule == r) "●" else "○", FgDimens.deltaSize, FgColors.textSecondary)
                Spacer(Modifier.width(10.dp))
                FgText(slRuleLabel(r), FgDimens.deltaSize, FgColors.foreground)
            }
        }
        FgText("どの規則でもSLが下がることはありません。", FgDimens.captionSize, FgColors.textMuted)

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = position != null) { position?.let(onAdd) }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            FgText(
                if (position == null) "入力が足りません" else "追加",
                FgDimens.ratingSize,
                if (position == null) FgColors.textMuted else FgColors.foreground,
            )
        }
    }
}

/** 引け後の終値入力。docs/price_source.md の (a)。 */
@Composable
fun ClosePriceForm(
    codes: List<String>,
    date: String,
    onEnter: (DailyClose) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        FgText("$date の終値", FgDimens.ratingSize, FgColors.foreground)
        FgText("入れた銘柄だけ判定します。未入力は割れ0件に数えません。",
            FgDimens.captionSize, FgColors.textMuted)
        Spacer(Modifier.height(12.dp))
        codes.forEach { code ->
            var value by remember(code, date) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FgText(code, FgDimens.deltaSize, FgColors.foreground, Modifier.width(72.dp))
                Field(value, KeyboardType.Decimal, Modifier.weight(1f)) {
                    value = it
                    it.trim().toDoubleOrNull()?.let { v -> onEnter(DailyClose(code, date, v)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun slRuleLabel(rule: SlRule): String = when (rule) {
    SlRule.NONE -> "なし(既定)"
    SlRule.BREAKEVEN -> "建値まで(含み益が出た日)"
    SlRule.ATR -> "ATR(データが揃った日)"
    SlRule.SWING -> "直近の確定スイング安値"
}

internal fun buildPosition(
    code: String, entry: String, sl: String, shares: String, openedAt: String, rule: SlRule,
): Position? {
    val e = entry.trim().toDoubleOrNull() ?: return null
    val s = sl.trim().toDoubleOrNull() ?: return null
    val n = shares.trim().toIntOrNull() ?: return null
    if (code.isBlank() || openedAt.isBlank() || e <= 0 || s <= 0 || n <= 0) return null
    return Position(code.trim(), e, s, n, openedAt.trim(), rule)
}

@Composable
private fun FieldRow(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FgText(label, FgDimens.deltaSize, FgColors.textSecondary, Modifier.width(88.dp))
        Field(value, type, Modifier.weight(1f), onChange)
    }
}

@Composable
private fun Field(value: String, type: KeyboardType, modifier: Modifier, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        textStyle = TextStyle(color = FgColors.foreground, fontSize = FgDimens.ratingSize),
    )
}
