// 票 06：Draft 确认界面——左原图对照（可缩放）、右可编辑字段表单。
// 确认 → Entry 落库；放弃 → 不留痕返回。确认前任意字段可编辑（验收项）。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
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
)

@OptIn(ExperimentalLayoutApi::class)
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
    var date by remember { mutableStateOf(draft.datePaid.take(10)) }
    var category by remember { mutableStateOf(draft.category) }

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

        // ---- 票 28-B：交叉验证失败 → 提示重点核对 ----
        if (draft.needsReview) {
            Text(
                "识别置信度较低，请重点核对金额与日期",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
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
        DateField(value = date, onValueChange = { date = it })

        // 类别：文本框 + 快选（类别列表来自类别体系，票 08；FlowRow 适配任意数量）
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
