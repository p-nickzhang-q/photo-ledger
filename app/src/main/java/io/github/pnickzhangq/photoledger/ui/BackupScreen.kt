// 票 11：备份与导出页。措辞纪律：备份 = JSON 可出可进（导入会替换现有数据，须二次确认）；
// 导出 = CSV 只出不进（Excel 分析用，不是恢复手段）。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BackupScreen(
    entryCount: Int,
    categoryCount: Int,
    message: String?,
    showRestoreConfirm: Boolean,
    onDismissRestoreConfirm: () -> Unit,
    onConfirmRestore: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportCsv: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "当前 ${entryCount} 条账目 / ${categoryCount} 个类别",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 备份（可回灌）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("备份（JSON，可回灌）", fontWeight = FontWeight.Bold)
                Text(
                    "包含全部账目与类别，不含照片。备份文件可随时导入恢复，导入会替换当前全部数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onExportBackup, Modifier.fillMaxWidth()) {
                    Text("导出备份到文件")
                }
                OutlinedButton(onClick = onImportBackup, Modifier.fillMaxWidth()) {
                    Text("从备份文件恢复")
                }
            }
        }

        // ---- 导出（只出不进）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("导出（CSV，只出不进）", fontWeight = FontWeight.Bold)
                Text(
                    "生成 UTF-8（带 BOM）CSV，Excel 直接打开中文不乱码，供阅读与分析。" +
                        "CSV 不是备份，不能导回 App。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onExportCsv, Modifier.fillMaxWidth()) {
                    Text("导出 CSV（Excel 用）")
                }
            }
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = onDismissRestoreConfirm,
            title = { Text("确认恢复备份？") },
            text = { Text("导入备份将清空当前全部账目与类别，并替换为备份文件的内容。\n\n此操作不可撤销，建议先「导出备份」留存现状。") },
            confirmButton = {
                TextButton(onClick = onConfirmRestore) { Text("替换并恢复") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestoreConfirm) { Text("取消") }
            },
        )
    }
}
