// 票 10：模型管理页——首启引导（无模型时即为引导页：说明用途/大小/来源）、
// 下载（源切换 + 进度 + 断点续传重试）、本地文件导入兜底、RAM 预检、OCR 三件套状态。
// 状态全部 hoist 到 MainActivity；本页只做呈现与回调。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pnickzhangq.photoledger.model.ModelManager
import java.io.File
import java.util.Locale

private fun formatGb(bytes: Long): String = String.format(Locale.US, "%.2f GB", bytes / 1e9)

@Composable
fun ModelManageScreen(
    modelFile: File?,
    ocrDetReady: Boolean,
    ocrRecReady: Boolean,
    ocrClsReady: Boolean,
    totalRamBytes: Long,
    downloadState: ModelManager.DownloadState,
    selectedSource: Int,
    onSourceChange: (Int) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onImportModel: () -> Unit,
    onImportOcr: () -> Unit,
    ocrDownloadState: ModelManager.DownloadState,
    onOcrDownloadStart: () -> Unit,
    onOcrDownloadCancel: () -> Unit,
    importMsg: String?,
    // 非空 = 首启引导（模型页是根页）：模型就绪显示「开始使用」，未就绪显示「暂不下载，先浏览」
    onExitOnboarding: (() -> Unit)?,
    onOpenDevTools: () -> Unit,
) {
    val ramOk = totalRamBytes >= ModelManager.MIN_RAM_BYTES
    val running = downloadState is ModelManager.DownloadState.Running

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 模型状态 ----
        if (modelFile != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✓ 识别模型已就绪", fontWeight = FontWeight.Bold)
                    Text("${modelFile.name}（${formatGb(modelFile.length())}）", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "导入识别全程离线可用，之后无需联网。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            onExitOnboarding?.let {
                Button(onClick = it, Modifier.fillMaxWidth()) { Text("开始使用") }
            }
        } else {
            // ---- 首启引导：模型页同时是首启引导页 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("欢迎使用照片记账", fontWeight = FontWeight.Bold)
                    Text(
                        "本 App 用端侧 AI 从订单截图中识别金额、日期和类别。" +
                            "首次使用需要下载识别模型（${ModelManager.SIZE_HINT}），" +
                            "下载完成后记账全程无需联网，截图数据不出手机。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            onExitOnboarding?.let {
                OutlinedButton(onClick = it, Modifier.fillMaxWidth()) { Text("暂不下载，先浏览") }
            }
        }

        // ---- RAM 预检 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val ramGb = String.format(Locale.US, "%.1f", totalRamBytes / 1e9)
                if (ramOk) {
                    Text("✓ 设备内存 $ramGb GB，满足运行要求（≥8GB）")
                } else {
                    Text("⚠ 设备内存 $ramGb GB（<8GB）", color = MaterialTheme.colorScheme.error)
                    Text(
                        "内存不足可能无法加载模型。已禁止下载，可尝试下方本地导入（风险自担）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ---- 下载 / 导入（模型未就绪或用户想重下时可见）----
        if (modelFile == null) {
            Text("下载模型（${ModelManager.SIZE_HINT}）", fontWeight = FontWeight.Bold)
            Column {
                ModelManager.SOURCES.forEachIndexed { i, s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selectedSource == i, onClick = { onSourceChange(i) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedSource == i, onClick = { onSourceChange(i) })
                        Text(s.label)
                    }
                }
            }

            when (val ds = downloadState) {
                is ModelManager.DownloadState.Running -> {
                    if (ds.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (ds.bytes.toDouble() / ds.totalBytes).toFloat() },
                            Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        if (ds.totalBytes > 0) {
                            "${formatGb(ds.bytes)} / ${formatGb(ds.totalBytes)}（${(ds.bytes * 100 / ds.totalBytes)}%）"
                        } else {
                            "已下载 ${formatGb(ds.bytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onCancelDownload) { Text("取消") }
                        Text(
                            "中断后重新开始可从断点续传",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
                is ModelManager.DownloadState.Failed -> {
                    Text("下载失败：${ds.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onStartDownload, Modifier.fillMaxWidth()) { Text("重试（断点续传）") }
                }
                ModelManager.DownloadState.Idle -> {
                    Button(onClick = onStartDownload, Modifier.fillMaxWidth(), enabled = ramOk) {
                        Text("开始下载")
                    }
                }
            }
        }

        // ---- 本地导入兜底 ----
        HorizontalDivider()
        Text("本地导入", fontWeight = FontWeight.Bold)
        Text(
            if (modelFile == null) "无法下载时，可用文件管理器选择 .litertlm 模型文件导入。" else "重新导入会替换现有模型。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImportModel) { Text("导入识别模型") }
            OutlinedButton(onClick = onImportOcr) { Text("导入 OCR 模型") }
        }
        Text(
            "OCR 三件套（RapidOCR 官方源，共约 16MB）：det " + (if (ocrDetReady) "✓" else "✗") +
                " · rec " + (if (ocrRecReady) "✓" else "✗") +
                " · cls " + (if (ocrClsReady) "✓" else "✗"),
            style = MaterialTheme.typography.bodySmall,
        )

        // ---- OCR 三件套下载（任一缺失时开放；与识别模型同一套下载管线）----
        if (!(ocrDetReady && ocrRecReady && ocrClsReady)) {
            when (val os = ocrDownloadState) {
                is ModelManager.DownloadState.Running -> {
                    if (os.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (os.bytes.toDouble() / os.totalBytes).toFloat() },
                            Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        "OCR 模型 ${formatGb(os.bytes)} / ${formatGb(os.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOcrDownloadCancel) { Text("取消") }
                }
                is ModelManager.DownloadState.Failed -> {
                    Text("OCR 下载失败：${os.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onOcrDownloadStart, Modifier.fillMaxWidth()) { Text("重试（断点续传）") }
                }
                ModelManager.DownloadState.Idle -> {
                    Button(onClick = onOcrDownloadStart, Modifier.fillMaxWidth()) {
                        Text("下载 OCR 模型（约 16MB）")
                    }
                }
            }
        }

        importMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        TextButton(onClick = onOpenDevTools, Modifier.align(Alignment.End)) {
            Text("开发工具（冒烟测试）", style = MaterialTheme.typography.labelSmall)
        }
    }
}
