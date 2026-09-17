// 票 09：汇总页——月度汇总（当月合计 + 历史各月）+ 当月类别汇总（合计 + 占比条）。
// 口径 = Entry.amountPaid（实付款），数据全部来自账目库只读查询，本页无写路径。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pnickzhangq.photoledger.data.CategoryTotal
import io.github.pnickzhangq.photoledger.data.MonthTotal

@Composable
fun SummaryScreen(
    monthTotals: List<MonthTotal>,
    categoryTotals: List<CategoryTotal>,
    currentMonth: String,
) {
    if (monthTotals.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("还没有账目", style = MaterialTheme.typography.titleMedium)
            Text(
                "导入截图或手工记账后，\n这里会给出月度与类别汇总。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val current = monthTotals.firstOrNull { it.month == currentMonth }
    val history = monthTotals.filter { it.month != currentMonth }
    val currentTotal = current?.total ?: 0.0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 月度汇总 ----
        item {
            Text("月度汇总", style = MaterialTheme.typography.titleMedium)
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "¥${formatAmountTotal(currentTotal)}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${formatMonth(currentMonth)} · 共 ${current?.count ?: 0} 笔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { HorizontalDivider() }

        // ---- 类别汇总（当月，含「其他」）----
        item {
            Text("类别汇总 · ${formatMonth(currentMonth)}", style = MaterialTheme.typography.titleMedium)
        }
        if (categoryTotals.isEmpty()) {
            item {
                Text(
                    "当月没有账目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(categoryTotals, key = { it.category }) { ct ->
                val fraction =
                    if (currentTotal > 0) (ct.total / currentTotal).toFloat().coerceIn(0f, 1f) else 0f
                val pct = if (currentTotal > 0) ct.total / currentTotal * 100 else 0.0
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ct.category, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "¥${formatAmountTotal(ct.total)} · ${"%.1f".format(pct)}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    // 占比条：宽度即占当月合计的比例
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
        item { HorizontalDivider() }

        // ---- 历史各月 ----
        item {
            Text("历史各月", style = MaterialTheme.typography.titleMedium)
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "还没有更早的账目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(history, key = { it.month }) { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatMonth(m.month), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        "¥${formatAmountTotal(m.total)} · ${m.count} 笔",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}
