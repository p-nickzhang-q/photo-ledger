// 票 06：Entry 详情/编辑 + 手工新增（共用表单）。删除时可选连图删（对话框）。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.pnickzhangq.photoledger.data.Entry
import java.io.File

/** 可编辑表单状态（编辑既有 Entry）。订单状态不再编辑（存量值由保存路径原样保留）。 */
data class EntryForm(
    var merchant: String,
    var amountPaid: String,
    var datePaid: String,
    var category: String,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryEditScreen(
    entry: Entry,
    photoDir: File?,               // 有原图时非空
    categories: List<String>,
    onSave: (EntryForm) -> Unit,
    onDelete: (alsoPhoto: Boolean) -> Unit,
) {
    var merchant by remember(entry.id) { mutableStateOf(entry.merchant) }
    var amount by remember(entry.id) { mutableStateOf(entry.amountPaid.toString()) }
    var date by remember(entry.id) { mutableStateOf(entry.datePaid.take(10)) }
    var category by remember(entry.id) { mutableStateOf(entry.category) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPhotoViewer by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (entry.photoPath != null && photoDir != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(File(photoDir, entry.photoPath)).build(),
                contentDescription = "原始截图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clickable { showPhotoViewer = true },
            )
        }

        OutlinedTextField(
            value = merchant, onValueChange = { merchant = it },
            label = { Text("商家") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("实付款金额") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        DateField(value = date, onValueChange = { date = it })
        OutlinedTextField(
            value = category, onValueChange = { category = it },
            label = { Text("类别") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            categories.forEach { c ->
                OutlinedButton(onClick = { category = c }) { Text(c, style = MaterialTheme.typography.labelMedium) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    onSave(EntryForm(merchant, amount, date, category))
                },
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.weight(1f),
            ) { Text("删除") }
        }
    }

    if (showPhotoViewer && photoDir != null && entry.photoPath != null) {
        Dialog(
            onDismissRequest = { showPhotoViewer = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FullscreenPhoto(File(photoDir, entry.photoPath)) { showPhotoViewer = false }
        }
    }

    if (showDeleteDialog) {
        var alsoPhoto by remember { mutableStateOf(entry.photoPath != null) }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除这条账目？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("「${entry.merchant.ifBlank { "（未命名）" }}」将不可恢复。")
                    if (entry.photoPath != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = alsoPhoto, onCheckedChange = { alsoPhoto = it })
                            Text("同时删除原始截图", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDelete(alsoPhoto) }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 全屏原图：双指缩放 + 拖动，点空白处关闭。 */
@Composable
private fun FullscreenPhoto(photo: File, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(photo).build(),
            contentDescription = "原始截图（全屏）",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offsetX, translationY = offsetY,
                )
                .pointerInput(Unit) {
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
    }
}
