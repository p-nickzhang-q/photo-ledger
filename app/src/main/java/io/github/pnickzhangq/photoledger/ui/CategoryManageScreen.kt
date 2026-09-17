// 票 08：类别管理页——新增/重命名/删除。「其他」是删类别的兜底（FALLBACK_CATEGORY），
// 不可删不可改名；删除前确认提示其下账目会归「其他」（仓库层执行，S3 测试覆盖）。
// 导航重构：顶栏与返回由外层 AppScaffold 统一供给。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
) {
    var addText by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }

    // 每次打开对话框重置输入与错误
    androidx.compose.runtime.LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            addText = ""
            addError = null
        }
    }

    // 底部留白避让 FAB
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
        items(categories, key = { it.id }) { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (c.name == FALLBACK_CATEGORY) {
                    Text(
                        "兜底类别",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    IconButton(onClick = {
                        renameTarget = c
                        renameText = c.name
                        renameError = null
                    }) { Icon(Icons.Filled.Edit, contentDescription = "重命名") }
                    IconButton(onClick = { deleteTarget = c }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            HorizontalDivider()
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
