// 票 04/05 冒烟与端到端界面：按钮组 + 滚动结果区。
package io.github.pnickzhangq.photoledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SmokeScreen(
    status: String,
    ocrResult: String,
    llmResult: String,
    e2eResult: String,
    imageReady: Boolean,
    llmReady: Boolean,
    onPickImage: () -> Unit,
    onPickModel: () -> Unit,
    onOcr: () -> Unit,
    onLlm: () -> Unit,
    onE2e: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickImage, enabled = true) { Text("1.选截图") }
            Button(onClick = onOcr, enabled = imageReady) { Text("2.OCR") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickModel, enabled = true) { Text("3.选GGUF") }
            Button(onClick = onLlm, enabled = llmReady) { Text("4.推理") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onE2e, enabled = imageReady) { Text("5.端到端→Draft") }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (e2eResult.isNotBlank()) {
                Text("── Draft ──", style = MaterialTheme.typography.labelLarge)
                Text(e2eResult, style = MaterialTheme.typography.bodyMedium)
            }
            if (ocrResult.isNotBlank()) {
                Text("── OCR ──", style = MaterialTheme.typography.labelLarge)
                Text(ocrResult, style = MaterialTheme.typography.bodySmall)
            }
            if (llmResult.isNotBlank()) {
                Text("── LLM ──", style = MaterialTheme.typography.labelLarge)
                Text(llmResult, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
