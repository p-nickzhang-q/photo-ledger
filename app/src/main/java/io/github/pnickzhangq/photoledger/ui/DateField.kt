// 日期选择字段：只读文本框 + 日历图标，点击弹 Material3 DatePicker。
// 值格式 yyyy-MM-dd（与模型契约一致）；空值/历史含时间值自动兼容（取前 10 位解析）。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "日期",
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    // DatePicker 用 UTC 毫秒；历史值可能带时间部分，取前 10 位解析
    val initialMillis = remember(value) {
        runCatching {
            LocalDate.parse(value.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
    }

    Box(modifier) {
        OutlinedTextField(
            value = value.take(10),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = "选择日期")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // 透明覆盖层：整行可点（readOnly 文本框会吃掉点击事件）
        Box(Modifier.matchParentSize().clickable { showPicker = true })
    }

    if (showPicker) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onValueChange(
                            java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        )
                    }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
