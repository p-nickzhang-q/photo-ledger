// 票 06：Draft 确认界面——左原图对照（可缩放）、右可编辑字段表单。
// 确认 → Entry 落库；放弃 → 不留痕返回。确认前任意字段可编辑（验收项）。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pnickzhangq.photoledger.engine.Draft

/** 确认界面的可编辑字段状态（初值来自 Draft，确认时整体提交）。 */
data class DraftForm(
    var merchant: String,
    var amountPaid: String,
    var datePaid: String,
    var category: String,
    var orderStatus: String,
)

@Composable
fun DraftConfirmScreen(
    draft: Draft,
    photoPath: String?,           // 私有目录照片路径（导入截图时非空）
    categories: List<String>,
    onConfirm: (form: DraftForm) -> Unit,
    onDiscard: () -> Unit,
) {
    var merchant by remember { mutableStateOf(draft.merchant) }
    var amount by remember { mutableStateOf(draft.amountPaid.toString()) }
    var date by remember { mutableStateOf(draft.datePaid) }
    var category by remember { mutableStateOf(draft.category) }
    var statusText by remember { mutableStateOf(draft.orderStatus) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 原图对照（可缩放）----
        if (photoPath != null) {
            ZoomablePhoto(photoPath = photoPath)
        } else {
            Text("（无截图——手工录入）", style = MaterialTheme.typography.bodySmall)
        }

        // ---- 可编辑字段 ----
        OutlinedTextField(
            value = merchant, onValueChange = { merchant = it },
            label = { Text("商家") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            label = { Text("实付款金额") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = date, onValueChange = { date = it },
            label = { Text("日期（${if (draft.dateSource == com.pnickzhangq.photoledger.engine.DateSource.PAYMENT_TIME) "付款时间" else "下单时间"}）") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = statusText, onValueChange = { statusText = it },
            label = { Text("订单状态") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        // 类别：文本框 + 快速选择（完整类别体系属票 08）
        OutlinedTextField(
            value = category, onValueChange = { category = it },
            label = { Text("类别") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            categories.take(4).forEach { c ->
                OutlinedButton(onClick = { category = c }) { Text(c, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            categories.drop(4).forEach { c ->
                OutlinedButton(onClick = { category = c }) { Text(c, style = MaterialTheme.typography.labelMedium) }
            }
        }

        // ---- 确认 / 放弃 ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    onConfirm(
                        DraftForm(
                            merchant = merchant,
                            amountPaid = amount,
                            datePaid = date,
                            category = category,
                            orderStatus = statusText,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("确认入账") }
            OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("放弃") }
        }
    }
}

/** 原图对照：双指缩放（pinch）+ 拖动。ContentScale 保持比例。 */
@Composable
private fun ZoomablePhoto(photoPath: String) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(java.io.File(photoPath)).build(),
        contentDescription = "原始截图",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
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
