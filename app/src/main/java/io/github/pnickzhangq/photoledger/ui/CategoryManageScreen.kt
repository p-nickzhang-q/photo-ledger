// 票 08：类别管理页——新增/重命名/删除。「其他」是删类别的兜底（FALLBACK_CATEGORY），
// 不可删不可改名；删除前确认提示其下账目会归「其他」（仓库层执行，S3 测试覆盖）。
// 导航重构：顶栏与返回由外层 AppScaffold 统一供给。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pnickzhangq.photoledger.engine.FALLBACK_CATEGORY
import io.github.pnickzhangq.photoledger.data.CategoryEntity

@Composable
fun CategoryManageScreen(
    categories: List<CategoryEntity>,
    // 新增对话框状态提升到外层（FAB 触发，AppScaffold 持有）；onResult：null=成功关框，非空=错误信息
    showAddDialog: Boolean,
    onAddDialogDismiss: () -> Unit,
    onAdd: (name: String, onResult: (String?) -> Unit) -> Unit,
    onRename: (id: Long, newName: String, onResult: (String?) -> Unit) -> Unit,
    onDelete: (id: Long, name: String) -> Unit,
    // 票 36：拖拽松手时按新顺序落库
    onReorder: (orderedIds: List<Long>) -> Unit = {},
) {
    var addText by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    // 票 36：拖动期间 pendingIds 接管本地顺序，松手落库；DB Flow 回流到一致顺序后释放
    var pendingIds by remember { mutableStateOf<List<Long>?>(null) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()

    // 票 37：「其他」是删类别兜底，不参与排序——从可拖拽集合摘出，恒渲染在末尾
    val fallback = remember(categories) { categories.firstOrNull { it.name == FALLBACK_CATEGORY } }
    val draggable = remember(categories) { categories.filter { it.name != FALLBACK_CATEGORY } }

    LaunchedEffect(draggable) {
        val pending = pendingIds
        if (pending != null && draggable.map { it.id } == pending) pendingIds = null
    }

    val shown = remember(draggable, pendingIds) {
        val ids = pendingIds
        if (ids == null) draggable
        else {
            val byId = draggable.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
    }

    // 拖拽回调闭包随 pointerInput 只在 id 变化时重建，普通变量会读到旧快照——用 updatedState 取当前值
    val currentIds by rememberUpdatedState(draggable.map { it.id })

    fun endDrag(commit: Boolean) {
        val ids = pendingIds
        if (commit && ids != null && ids != currentIds) onReorder(ids) else pendingIds = null
        draggingId = null
        dragOffset = 0f
    }

    // 每次打开对话框重置输入与错误
    androidx.compose.runtime.LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            addText = ""
            addError = null
        }
    }

    // 底部留白避让 FAB
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
    ) {
        items(shown, key = { it.id }) { c ->
            val dragging = draggingId == c.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (dragging) Modifier else Modifier.animateItem())
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .background(if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .padding(vertical = 6.dp),
            ) {
                // 拖拽热区只覆盖「手柄 + 名称」，避开右侧重命名/删除按钮（长按不误触操作）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(c.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = c.id
                                    pendingIds = currentIds
                                    dragOffset = 0f
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    dragOffset += delta.y
                                    val current = pendingIds ?: return@detectDragGesturesAfterLongPress
                                    val from = current.indexOf(c.id)
                                    if (from < 0) return@detectDragGesturesAfterLongPress
                                    // 行高从 LazyListState 取，越半行即与邻居交换
                                    val rowHeight = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == from }?.size?.toFloat()
                                        ?: return@detectDragGesturesAfterLongPress
                                    if (rowHeight <= 0f) return@detectDragGesturesAfterLongPress
                                    val to = when {
                                        dragOffset > rowHeight / 2f && from < current.lastIndex -> from + 1
                                        dragOffset < -rowHeight / 2f && from > 0 -> from - 1
                                        else -> return@detectDragGesturesAfterLongPress
                                    }
                                    pendingIds = current.toMutableList().apply { add(to, removeAt(from)) }
                                    dragOffset -= (to - from) * rowHeight
                                },
                                onDragEnd = { endDrag(commit = true) },
                                onDragCancel = { endDrag(commit = false) },
                            )
                        },
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = "长按拖动排序",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
                IconButton(onClick = {
                    renameTarget = c
                    renameText = c.name
                    renameError = null
                }) { Icon(Icons.Filled.Edit, contentDescription = "重命名") }
                IconButton(onClick = { deleteTarget = c }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
        }
        // 票 37：兜底类别固定末尾，无拖拽把手、不可拖动
        fallback?.let { f ->
            item(key = "fallback") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Spacer(Modifier.width(28.dp))
                    Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        "兜底类别",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    // ---- 重命名对话框 ----
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名「${target.name}」") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it; renameError = null },
                    label = { Text("新名称") },
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = { renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target.id, renameText) { result ->
                        if (result == null) renameTarget = null else renameError = result
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // ---- 新增对话框（与重命名同构：成功关框，失败框内报错）----
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = onAddDialogDismiss,
            title = { Text("新增类别") },
            text = {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it; addError = null },
                    label = { Text("类别名") },
                    singleLine = true,
                    isError = addError != null,
                    supportingText = { addError?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAdd(addText) { result ->
                        if (result == null) onAddDialogDismiss() else addError = result
                    }
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = onAddDialogDismiss) { Text("取消") }
            },
        )
    }

    // ---- 删除确认：其下账目归「其他」 ----
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.name}」？") },
            text = { Text("该类别下的账目不会删除，将归入「$FALLBACK_CATEGORY」。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id, target.name)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}
