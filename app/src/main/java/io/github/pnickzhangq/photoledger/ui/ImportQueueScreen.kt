// 票 07：导入队列屏。逐项显示截图名与状态（排队/提取中/成功/失败/重复），
// 实时刷新（StateFlow）。Done 项点击进确认流；Failed 项给「手工录入」入口。
// 导航重构：顶栏与返回由外层 AppScaffold 统一供给；空态给导入 CTA（可直接进入本页）。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.pnickzhangq.photoledger.data.ImportItem
import io.github.pnickzhangq.photoledger.data.ImportState

@Composable
fun ImportQueueScreen(
    items: List<ImportItem>,
    onImportFromGallery: () -> Unit,
    onConfirmDraft: (ImportItem) -> Unit,
    onManualEntry: (ImportItem) -> Unit,
) {
    var previewItem by remember { mutableStateOf<ImportItem?>(null) }
    if (items.isEmpty()) {
        // 空态 = 行动邀请：直达本页（顶栏队列图标）时也能一步开始
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("队列还空着", style = MaterialTheme.typography.titleMedium)
            Text(
                "从相册多选订单截图，或在相册/微信里「分享」到照片记账，\n逐张识别后在这里确认入账。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImportFromGallery) { Text("从相册导入截图") }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items) { item ->
                ImportItemCard(
                    item,
                    onConfirmDraft,
                    onManualEntry,
                    onImageClick = { previewItem = item },
                )
            }
            item {
                OutlinedButton(
                    onClick = onImportFromGallery,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text("继续导入") }
            }
        }
    }

    previewItem?.let { item ->
        FullscreenPhotoPreview(item = item, onDismiss = { previewItem = null })
    }
}

@Composable
private fun ImportItemCard(
    item: ImportItem,
    onConfirmDraft: (ImportItem) -> Unit,
    onManualEntry: (ImportItem) -> Unit,
    onImageClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = when (item.state) {
            is ImportState.Failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            is ImportState.Duplicate -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            else -> CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 缩略图：让用户能对上是哪张截图（截断 128px 解码，避免整图内存占用）；点击全屏放大
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.bytes)
                    .size(128)
                    .build(),
                contentDescription = "查看大图",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onImageClick),
                contentScale = ContentScale.Crop,
            )
            val icon: androidx.compose.ui.graphics.vector.ImageVector
            val tint: Color
            when (item.state) {
                is ImportState.Pending -> { icon = Icons.Filled.Schedule; tint = Color.Gray }
                is ImportState.Processing -> { icon = Icons.Filled.Schedule; tint = MaterialTheme.colorScheme.primary }
                is ImportState.Done -> { icon = Icons.Filled.CheckCircle; tint = MaterialTheme.colorScheme.primary }
                is ImportState.Confirmed -> { icon = Icons.Filled.CheckCircle; tint = Color(0xFF2E7D32) }
                is ImportState.Failed -> { icon = Icons.Filled.Error; tint = MaterialTheme.colorScheme.error }
                is ImportState.Duplicate -> { icon = Icons.Filled.ContentCopy; tint = Color.Gray }
            }
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = when (val s = item.state) {
                        is ImportState.Pending -> "排队中"
                        is ImportState.Processing -> "提取中…"
                        is ImportState.Done ->
                            s.drafts.joinToString("\n") { d ->
                                "✓ ${d.merchant} ¥${d.amountPaid} @" +
                                    d.datePaid.take(10).ifEmpty { "无日期（入账时填今天）" }
                            }
                        is ImportState.Confirmed ->
                            s.drafts.joinToString("\n") { d ->
                                "✓ ${d.merchant} ¥${d.amountPaid} @${d.datePaid.take(10)}"
                            } + "\n已入账，可在账目列表点开编辑"
                        is ImportState.Failed -> "失败：${s.reason}"
                        is ImportState.Duplicate -> "这张已导入过"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (item.state) {
                is ImportState.Done -> {
                    if ((item.state as ImportState.Done).drafts.isNotEmpty()) {
                        Button(onClick = { onConfirmDraft(item) }) { Text("确认") }
                    }
                }
                is ImportState.Failed -> {
                    OutlinedButton(onClick = { onManualEntry(item) }) { Text("手工录入") }
                }
                else -> {}
            }
        }
    }
}

/** 全屏原图预览：双指缩放（1-5x）+ 拖动，右上角关闭，点黑底也可关闭。 */
@Composable
private fun FullscreenPhotoPreview(item: ImportItem, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            var scale by remember(item) { mutableStateOf(1f) }
            var offsetX by remember(item) { mutableStateOf(0f) }
            var offsetY by remember(item) { mutableStateOf(0f) }

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(item.bytes).build(),
                contentDescription = "原图预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offsetX, translationY = offsetY,
                    )
                    .pointerInput(item) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
