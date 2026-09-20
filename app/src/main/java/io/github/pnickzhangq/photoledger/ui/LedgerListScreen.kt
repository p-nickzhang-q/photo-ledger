// 票 06：流水列表（倒序 + 缩略图，点开详情看原图）。票 09：+按月分组（月内保持倒序）。
// 设计「账本墨绿」：月头带当月合计（绿），月组之间撕票虚线，行间无线靠间距；金额用 AmountText。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.pnickzhangq.photoledger.data.Entry
import io.github.pnickzhangq.photoledger.data.MonthTotal
import java.io.File

@Composable
fun LedgerListScreen(
    entries: List<Entry>,
    thumbDir: File,
    photoDir: File,
    emptyHint: String,
    onEntryClick: (Entry) -> Unit,
    onImportFromGallery: () -> Unit = {},   // 票 07：相册多选入口（空态/工具栏均可触发）
    monthTotals: List<MonthTotal> = emptyList(), // 设计：月头当月合计（数据来自票 09 查询）
) {
    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emptyHint, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onImportFromGallery, Modifier.padding(top = 16.dp)) {
                Text("从相册导入截图")
            }
        }
        return
    }
    val totalsByMonth = remember(monthTotals) { monthTotals.associateBy { it.month } }
    // 月份分组：entries 已按 date_paid DESC 排序，月份变化处插标题（月内倒序天然保持）。
    // 撕票虚线画在月头顶部（首组除外）——结构即信息：虚线只出现在月份分组处。
    // 注意不要给虚线单独发 LazyColumn 行：data object 单例的 indexOf 会让 key 重复（真机崩溃前科）。
    val rows = remember(entries) {
        buildList {
            var lastMonth: String? = null
            entries.forEach { entry ->
                val month = entry.datePaid.take(7)
                if (month != lastMonth) {
                    add(LedgerRow.Header(month, withPerforation = lastMonth != null))
                    lastMonth = month
                }
                add(LedgerRow.Item(entry))
            }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            rows,
            key = {
                when (it) {
                    is LedgerRow.Header -> "h-${it.month}"
                    is LedgerRow.Item -> "e-${it.entry.id}"
                }
            },
            contentType = { if (it is LedgerRow.Header) "header" else "entry" },
        ) { row ->
            when (row) {
                is LedgerRow.Header -> MonthHeader(row.month, totalsByMonth[row.month], row.withPerforation)
                is LedgerRow.Item -> EntryRow(row.entry, thumbDir, photoDir, onEntryClick)
            }
        }
    }
}

private sealed class LedgerRow {
    data class Header(val month: String, val withPerforation: Boolean) : LedgerRow()
    data class Item(val entry: Entry) : LedgerRow()
}

/** 月头：左月份、右当月合计（账绿）——翻账本时最想要的数字就在分组标题上。 */
@Composable
private fun MonthHeader(month: String, total: MonthTotal?, withPerforation: Boolean) {
    Column {
        if (withPerforation) PerforationDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                formatMonth(month),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (total != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "合计 ${total.count} 笔",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AmountText(total.total, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** 撕票虚线：只在月份分组之间出现（见 rows 构建），别处不用。 */
@Composable
private fun PerforationDivider() {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(1.dp)
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = size.height,
                    pathEffect = dash,
                )
            },
    )
}

@Composable
private fun EntryRow(entry: Entry, thumbDir: File, photoDir: File, onEntryClick: (Entry) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEntryClick(entry) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
                // 缩略图：优先 thumbs 文件；队列确认的历史条目无缩略图时回退原图
                // （Coil size(128) 降采样解码，不整图进内存）。两者皆无（手工无图）占位。
                val thumbModel = when {
                    entry.thumbPath != null -> File(thumbDir, entry.thumbPath)
                    entry.photoPath != null -> File(photoDir, entry.photoPath)
                    else -> null
                }
                if (thumbModel != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thumbModel)
                            .size(128)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp),
                    )
                } else {
                    Text("📒", modifier = Modifier.size(width = 44.dp, height = 44.dp))
                }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // 主标题回退链：商家（手填）→ 类别——商家退出模型识别后列表仍有一眼可读的主信息
            Text(
                entry.merchant.ifBlank { entry.category.ifBlank { "（未命名）" } },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                if (entry.merchant.isNotBlank()) "${entry.datePaid.take(10)} · ${entry.category}"
                else entry.datePaid.take(10),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        AmountText(entry.amountPaid, style = MaterialTheme.typography.titleMedium)
    }
}

/** 金额展示：整数去 .0，两位小数内原样。 */
internal fun formatAmount(a: Double): String =
    if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString()

/** 汇总金额：两位小数舍入后展示（SUM 累加的浮点尾差清掉）。 */
internal fun formatAmountTotal(a: Double): String = formatAmount(kotlin.math.round(a * 100) / 100)

/** "2026-09" → "2026年9月"；空串（无日期历史数据）→ "无日期"。 */
internal fun formatMonth(month: String): String =
    if (month.isBlank()) "无日期"
    else month.split("-").let { "${it[0]}年${it[1].toInt()}月" }
