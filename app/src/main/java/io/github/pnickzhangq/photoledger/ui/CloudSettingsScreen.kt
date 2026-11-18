// 票 42：识别引擎设置页。端侧内置 / 云端大模型二选一，默认端侧。
// 首次切云端必须确认隐私告知（明确会上传截图原图到用户填写的 host）；
// Key 存私有 SharedPreferences，界面默认掩码，「测试连接」只做一次最小请求。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.pnickzhangq.photoledger.cloud.CloudConfig
import io.github.pnickzhangq.photoledger.cloud.RecognitionEngine
import kotlinx.coroutines.launch

/** 设置的编辑态（进入页面时从 prefs 快照，改动即时回写）。 */
class CloudSettingsState(config: CloudConfig) {
    var engine by mutableStateOf(config.engine)
    var baseUrl by mutableStateOf(config.baseUrl)
    var model by mutableStateOf(config.model)
    var apiKey by mutableStateOf(config.apiKey)
    var fastMode by mutableStateOf(config.fastMode)

    fun toConfig() = CloudConfig(engine, baseUrl.trim(), model.trim(), apiKey.trim(), fastMode)
}

private sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data class Result(val text: String, val ok: Boolean) : TestState()
}

@Composable
fun CloudSettingsScreen(
    state: CloudSettingsState,
    consentDone: Boolean,
    message: String?,
    onConfigChange: (CloudConfig) -> Unit,
    onConsentAndEnable: () -> Unit,
    onTestConnection: suspend () -> String,
) {
    var showConsent by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    val scope = rememberCoroutineScope()

    fun persist() = onConfigChange(state.toConfig())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text("识别引擎", style = MaterialTheme.typography.titleMedium)
        Text(
            "端侧模式全程离线，但只识别金额、日期、类别；云端模式会上传压缩后的截图，可额外识别商家与具体内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        EngineOption(
            selected = state.engine == RecognitionEngine.DEVICE,
            title = "端侧内置",
            description = "本地 OCR + 小模型，截图与账目不出设备；不提取商家与具体内容",
            onClick = {
                state.engine = RecognitionEngine.DEVICE
                persist()
            },
        )
        EngineOption(
            selected = state.engine == RecognitionEngine.CLOUD,
            title = "云端大模型",
            description = "上传截图到 OpenAI 兼容视觉接口，提取商家、具体内容、金额、日期、类别",
            onClick = {
                if (consentDone) {
                    state.engine = RecognitionEngine.CLOUD
                    persist()
                } else {
                    showConsent = true
                }
            },
        )

        HorizontalDivider()

        Text("云端接口", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = { state.baseUrl = it; persist() },
            label = { Text("接口地址") },
            supportingText = { Text("仅支持 https；填到 /v1 或完整 /chat/completions 均可") },
            singleLine = true,
            enabled = state.engine == RecognitionEngine.CLOUD,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.model,
            onValueChange = { state.model = it; persist() },
            label = { Text("模型名") },
            supportingText = { Text("需支持图片输入，例如某家的视觉模型名") },
            singleLine = true,
            enabled = state.engine == RecognitionEngine.CLOUD,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { state.apiKey = it; persist() },
            label = { Text("API Key") },
            supportingText = { Text("只保存在本机应用私有目录；自建网关不校验时可留空") },
            singleLine = true,
            enabled = state.engine == RecognitionEngine.CLOUD,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("快速模式（关闭深度思考）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "DeepSeek 推理模型直接输出 JSON，不再先思考；复杂截图若识别不全可关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.fastMode,
                onCheckedChange = { state.fastMode = it; persist() },
                enabled = state.engine == RecognitionEngine.CLOUD,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    testState = TestState.Testing
                    scope.launch {
                        testState = try {
                            TestState.Result(onTestConnection(), ok = true)
                        } catch (t: Throwable) {
                            TestState.Result(t.message ?: "连接失败", ok = false)
                        }
                    }
                },
                enabled = state.engine == RecognitionEngine.CLOUD &&
                    state.baseUrl.isNotBlank() && state.model.isNotBlank() &&
                    testState !is TestState.Testing,
            ) { Text(if (testState is TestState.Testing) "测试中…" else "测试连接") }

            when (val t = testState) {
                is TestState.Result -> Text(
                    t.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                else -> {}
            }
        }

        Text(
            "云端失败会自动回退端侧识别（需已安装端侧模型）；回退会在导入队列里标注。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showConsent) {
        val host = state.baseUrl.substringAfter("://", "").substringBefore("/")
            .ifBlank { "你填写的接口地址" }
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("开启云端识别？") },
            text = {
                Text(
                    "开启后，导入的截图会被压缩并上传到 $host。截图中可能包含商户、商品、金额、" +
                        "订单号等信息。只有在你信任该服务时才开启；你随时可以切回端侧识别。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConsent = false
                    state.engine = RecognitionEngine.CLOUD
                    onConsentAndEnable()
                }) { Text("我已知晓，开启") }
            },
            dismissButton = {
                TextButton(onClick = { showConsent = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EngineOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
