// 票 06：流水基础列表（倒序 + 缩略图）。按月分组与汇总属票 09，本票不做。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
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
    }
}

/** 金额展示：整数去 .0，两位小数内原样。 */
internal fun formatAmount(a: Double): String =
    if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString()
