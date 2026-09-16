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
        try {
            val response = conversation.sendMessage(
                text = prompt,
                responseFormat = ResponseFormat.json(grammarToJsonSchema(grammar)),
                maxOutputToken = maxOutputToken,
            )
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
         * LLGuidance 对 enum 约束的输出 quirk（票 13 实测）。只处理字符串值，
         * 正则锚定「冒号后紧跟 \" 开头、行尾 \" 结束」的值形态。
         */
        internal fun stripDoubleEncodedStringValues(json: String): String =
            json.replace(Regex("(\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*)\"\\\\\"([^\\\\]*?)\\\\\"\"")) { m ->
                "${m.groupValues[1]}\"${m.groupValues[2]}\""
            }

        /**
         * 剥 GBNF 字符串字面量的引号：GrammarGenerator 产出的是 GBNF 转义引号形态
         * （`\"餐饮\"`），非裸引号。分三种形态处理：`\"X\"` / `"X"` / `X`。
         */
        private fun stripGbnfQuotes(token: String): String {
            val t = token.trim()
            return when {
                t.startsWith("\\\"") && t.endsWith("\\\"") && t.length >= 4 ->
                    t.substring(2, t.length - 2)
                t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 ->
                    t.substring(1, t.length - 1)
                else -> t
            }
        }

        /**
         * GBNF → JSON Schema 转译（本仓 Draft 契约专用，非通用转换器）：
         * 1. 解析 date 规则的候选清单（withDateAlternatives 注入的 `"YYYY-MM-DD HH:MM:SS" | ... | ""`）
         *    → datePaid 的 enum；无清单时 datePaid 为自由 string
         * 2. 其余字段按 ExtractionSchema 契约重建（与 GrammarGenerator 语义一致）
         * 3. category/currency/dateSource 枚举从 grammar 的枚举行提取
         */
        internal fun grammarToJsonSchema(grammar: String): String {
            // date 候选清单：date ::= "..." | "..." | ""
            val dateLine = grammar.lineSequence().firstOrNull { it.startsWith("date ::=") }
            val dateCandidates = dateLine
                ?.removePrefix("date ::=")
                ?.split("|")
                ?.map(::stripGbnfQuotes)
                ?.filter { it.isNotEmpty() }
                ?.toList()
                ?: emptyList()

            // category 枚举：category ::= "餐饮" | "购物" | ...
            fun enumFrom(ruleName: String): List<String> {
                val line = grammar.lineSequence().firstOrNull { it.startsWith("$ruleName ::=") } ?: return emptyList()
                return line.removePrefix("$ruleName ::=").split("|").map(::stripGbnfQuotes).filter { it.isNotEmpty() }
            }

            val categories = enumFrom("category")
            val currencies = enumFrom("currency")
            val dateSources = enumFrom("dateSource")

            fun enumClause(name: String, values: List<String>, fallbackType: String = "string"): String =
                if (values.isEmpty()) {
                    "\"$name\":{\"type\":\"$fallbackType\"}"
                } else {
                    val enumItems = values.joinToString(",") { v -> "\"" + v + "\"" }
                    "\"$name\":{\"type\":\"string\",\"enum\":[$enumItems]}"
                }

            return buildString {
                append("{")
                append("\"type\":\"object\",")
                append("\"properties\":{")
                append("\"merchant\":{\"type\":\"string\"},")
                append("\"amountPaid\":{\"type\":\"number\"},")
                append(enumClause("currency", currencies)).append(",")
                append(enumClause("datePaid", dateCandidates)).append(",")
                append(enumClause("dateSource", dateSources)).append(",")
                append("\"orderStatus\":{\"type\":\"string\"},")
                append(enumClause("category", categories))
                append("},")
                append("\"required\":[\"merchant\",\"amountPaid\",\"currency\",\"datePaid\",\"dateSource\",\"orderStatus\",\"category\"],")
                append("\"additionalProperties\":false")
                append("}")
            }
        }
    }
}
