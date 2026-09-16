// 票 13：LiteRT-LM 推理后端（速度破局评估）。
// LlmTransport 的第三实现：Engine/Conversation + ResponseFormat JSON Schema 约束
// （LLGuidance 后端）替代 GBNF。引擎核心零改动——grammar 参数（GBNF 文本）在此
// 转译为等价 JSON Schema：date 规则里的候选清单转 enum，其余按 Draft 契约重建。
package io.github.pnickzhangq.photoledger.ocr

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.pnickzhangq.photoledger.engine.GrammarGenerator
import com.pnickzhangq.photoledger.engine.LlmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * @param modelPath .litertlm 文件路径
 * @param backend CPU/GPU；真机默认 GPU（OpenCL，vivo 同厂基准 580/21 tok/s）
 */
class LitertLlmTransport(
    private val modelPath: String,
    private val backend: Backend = Backend.GPU(),
    private val maxOutputToken: Int = 256,
) : LlmTransport {

    @Volatile private var engine: Engine? = null

    /** 初始化（重操作，10s 级）——首次 completeText 惰性触发或调用方显式预加载。 */
    fun ensureLoaded() {
        if (engine != null) return
        val t0 = System.currentTimeMillis()
        val e = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
            ),
        )
        e.initialize()
        engine = e
        Log.i(TAG, "SMOKE_LITERT_LOAD_OK ms=${System.currentTimeMillis() - t0} backend=${backend::class.simpleName}")
    }

    override suspend fun complete(imageData: ByteArray, imageMime: String, prompt: String, grammar: String): String =
        throw UnsupportedOperationException("端侧 LiteRT 路线只走 OCR+文本（ADR-0004），不支持图像直入")

    override suspend fun completeText(prompt: String, grammar: String): String = withContext(Dispatchers.Default) {
        val e = engine ?: run { ensureLoaded(); engine!! }
        val conversation = e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0), // 贪心，对齐 llama.cpp
                maxOutputToken = maxOutputToken,
                enableResponseFormat = true,
            ),
        )
        val t0 = System.currentTimeMillis()
        val schema = GrammarGenerator.toJsonSchema(grammar)
        try {
            val response = try {
                conversation.sendMessage(
                    text = prompt,
                    responseFormat = ResponseFormat.json(schema),
                    maxOutputToken = maxOutputToken,
                )
            } catch (t: Throwable) {
                // 真机 schema 问题桌面未必复现（票 07：无日期截图分支）——失败时把 schema 落日志
                Log.w(TAG, "LITERT_SEND_FAIL schema=$schema", t)
                throw t
            }
            // 拼流式 Message 块（同步返回的是完整消息，取 text）
            val out = response.contents.contents.joinToString("") { c ->
                (c as? com.google.ai.edge.litertlm.Content.Text)?.text ?: ""
            }
            Log.i(TAG, "SMOKE_LITERT_OK ms=${System.currentTimeMillis() - t0} len=${out.length} out=$out")
            // 票 13 实测：LLGuidance 约束下 JSON Schema enum 字段输出双重编码
            // （"currency": "\"CNY\""），字符串字段值带一层字面引号。出口清洗：
            // 把 "…"（字面引号包裹的值）还原为裸值。自由字符串字段（merchant 等）
            // 输出正常不带双引号，不受影响；只剥「整体为 \"...\"」形态的值。
            stripDoubleEncodedStringValues(out)
        } finally {
            conversation.close()
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        private const val TAG = "photoledger-smoke"

        /**
         * 清洗 JSON 字符串值的字面引号：`"key": "\"value\""` → `"key": "value"`。
         * 根因（票 07 定位）曾是 schema enum 值双层引号（stripGbnfQuotes 只剥一层），
         * GrammarGenerator.toJsonSchema 修对后此清洗仅为兜底；只剥「整体为 \"...\"」形态的值。
         */
        internal fun stripDoubleEncodedStringValues(json: String): String =
            json.replace(Regex("(\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*)\"\\\\\"([^\\\\]*?)\\\\\"\"")) { m ->
                "${m.groupValues[1]}\"${m.groupValues[2]}\""
            }
    }
}
