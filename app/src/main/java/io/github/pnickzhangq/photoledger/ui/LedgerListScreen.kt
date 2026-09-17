// 票 06：流水列表（倒序 + 缩略图，点开详情看原图）。票 09：+按月分组（月内保持倒序）。
// 汇总页属票 09 的 SummaryScreen，本屏不做汇总。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.pnickzhangq.photoledger.data.Entry
import java.io.File

@Composable
fun LedgerListScreen(
    entries: List<Entry>,
    thumbDir: File,
    emptyHint: String,
    onEntryClick: (Entry) -> Unit,
    onImportFromGallery: () -> Unit = {},   // 票 07：相册多选入口（空态/工具栏均可触发）
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
    // 月份分组：entries 已按 date_paid DESC 排序，月份变化处插标题（月内倒序天然保持）
    val rows = remember(entries) {
        buildList {
            var lastMonth: String? = null
            entries.forEach { entry ->
                val month = entry.datePaid.take(7)
                if (month != lastMonth) {
                    add(LedgerRow.Header(month))
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
                is LedgerRow.Header -> Text(
                    formatMonth(row.month),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                is LedgerRow.Item -> EntryRow(row.entry, thumbDir, onEntryClick)
            }
        }
    }
}

private sealed class LedgerRow {
    data class Header(val month: String) : LedgerRow()
    data class Item(val entry: Entry) : LedgerRow()
}

@Composable
private fun EntryRow(entry: Entry, thumbDir: File, onEntryClick: (Entry) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEntryClick(entry) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // 缩略图（无图占位）
        if (entry.thumbPath != null) {
            AsyncImage(
                model = File(thumbDir, entry.thumbPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Text("📒", modifier = Modifier.size(width = 44.dp, height = 44.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.merchant.ifBlank { "（未命名）" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            Text(
                "${entry.datePaid.take(10)} · ${entry.category}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        Text(
            "¥${formatAmount(entry.amountPaid)}",
            style = MaterialTheme.typography.titleMedium,
        )
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
