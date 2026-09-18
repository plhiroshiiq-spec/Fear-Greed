package com.sumideck.fg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sumideck.fg.CollapsedRow
import com.sumideck.fg.LineMode
import com.sumideck.fg.PanelState

/**
 * SPEC.md 5.1章 案E「帯と推移」。上から「数値 → 位置の帯 → 推移線」の3段。
 * 半円ゲージは**不採用**(5.1章)。
 *
 * 計算は [com.sumideck.fg.PanelBuilder] で終わっているので、ここは描くだけにする。
 * 再コンポーズのたびに折れ線やスイング点を計算し直さないため(5.2章の検収)。
 *
 * @param expanded 展開時は4段目(7要素の横棒と4値)も描く。既定は常時表示(6.2章)。
 * @param collapsed 折りたたみ表示にするか。**畳んでも数値は残す**(6.3章)。
 *
 * SUMI DECK に取り込むときは Text / Modifier の流儀を既存側に合わせること(5章)。
 * ここでは素の Compose Foundation だけを使い、Material への依存を持たせていない。
 */
@Composable
fun FearGreedPanel(
    state: PanelState,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    collapsed: Boolean = false,
    lineMode: LineMode = LineMode.HORIZONTAL_ONLY,
) {
    if (collapsed) {
        // 6.3章: 畳んでも 名前 / 数値 / 区分名 / 前日差 / 連続日数 は残す。
        // 落とすのは推移線・補助線・目盛り数字であって、数値ではない。
        CollapsedLine(remember(state) { CollapsedRow.of(state) }, modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        ScoreRow(state)

        Spacer(Modifier.height(FgDimens.bandTopGap))
        PositionBand(state)

        Spacer(Modifier.height(FgDimens.chartTopGap))
        HistoryChart(state)
        Spacer(Modifier.height(6.dp))
        FgText("過去${state.series.size}営業日", FgDimens.captionSize, FgColors.textMuted)
        OverlayLegend(state, lineMode)

        if (expanded) {
            Spacer(Modifier.height(20.dp))
            ComponentBars(state)
            Spacer(Modifier.height(12.dp))
            ReferenceValues(state)
        }
    }
}

/** 1段目 数値行。スコア46sp Light / 区分名16sp / 右端に前日差と連続日数を14sp。 */
@Composable
private fun ScoreRow(state: PanelState) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        FgText(
            text = state.scoreText ?: "—",
            size = FgDimens.scoreSize,
            color = FgColors.foreground,
            weight = FontWeight.Light,
        )
        Spacer(Modifier.width(10.dp))
        FgText(
            text = state.rating?.ja ?: "",
            size = FgDimens.ratingSize,
            color = FgColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        // 6.4章: データが来ていないときは数値の代わりに更新遅延と最終取得日を出す。
        val right = if (!state.hasScore || state.stale) {
            state.lastUpdatedLabel?.let { "— 更新遅延($it)" } ?: "— 更新遅延"
        } else {
            listOfNotNull(state.deltaText, state.streakText).joinToString(" ")
        }
        FgText(right, FgDimens.deltaSize, FgColors.textSecondary, textAlign = TextAlign.End)
    }
}

/** 折りたたみ時の1行。細い水平バーは補助として添えてよいが、数値の代わりにはしない(6.3章)。 */
@Composable
private fun CollapsedLine(row: CollapsedRow, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FgText(row.label, FgDimens.deltaSize, FgColors.textSecondary)
        Spacer(Modifier.width(8.dp))
        if (row.staleText != null) {
            FgText(row.staleText, FgDimens.deltaSize, FgColors.textSecondary)
        } else {
            FgText(row.scoreText ?: "—", FgDimens.ratingSize, FgColors.foreground)
            Spacer(Modifier.width(8.dp))
            FgText(row.ratingText ?: "", FgDimens.deltaSize, FgColors.textSecondary)
            Spacer(Modifier.weight(1f))
            FgText(
                listOfNotNull(row.deltaText, row.streakText).joinToString(" "),
                FgDimens.deltaSize,
                FgColors.textSecondary,
            )
        }
    }
}

/** 4段目(展開時)。7要素の横棒。 */
@Composable
private fun ComponentBars(state: PanelState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.components.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FgText(c.nameJa, FgDimens.captionSize, FgColors.textSecondary, Modifier.width(96.dp))
                ComponentBar(c.score)
                Spacer(Modifier.width(8.dp))
                FgText(
                    c.score?.let { com.sumideck.fg.Display.toInt(it).toString() } ?: "—",
                    FgDimens.captionSize,
                    FgColors.foreground,
                )
            }
        }
    }
}

/** 4段目(展開時)。前日 / 1週 / 1か月 / 1年の4値。 */
@Composable
private fun ReferenceValues(state: PanelState) {
    val items = listOf(
        "前日" to state.prevClose,
        "1週" to state.w1,
        "1か月" to state.m1,
        "1年" to state.y1,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { (label, value) ->
            Column {
                FgText(label, FgDimens.captionSize, FgColors.textMuted)
                FgText(
                    value?.let { com.sumideck.fg.Display.toInt(it).toString() } ?: "—",
                    FgDimens.ratingSize,
                    FgColors.foreground,
                )
            }
        }
    }
}
