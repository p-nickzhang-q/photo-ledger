// 票 13：LiteRT-LM 桌面（JVM）transport——闸门质量对照用。
// 与 app 模块 LitertLlmTransport 同构（无 android.util.Log；GBNF→JSON Schema 转译一致）。
package com.pnickzhangq.photoledger.cli

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.pnickzhangq.photoledger.engine.LlmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LitertJvmTransport(
    private val modelPath: String,
    private val backend: Backend = Backend.CPU(),
    private val maxOutputToken: Int = 256,
) : LlmTransport, AutoCloseable {

    @Volatile private var engine: Engine? = null

    fun ensureLoaded() {
        if (engine != null) return
        val e = Engine(EngineConfig(modelPath = modelPath, backend = backend))
        e.initialize()
        engine = e
        println("litert engine ready: $modelPath (${backend.name})")
    }

    override suspend fun complete(imageData: ByteArray, imageMime: String, prompt: String, grammar: String): String =
        throw UnsupportedOperationException("LiteRT 路线只走 OCR+文本（ADR-0004）")

    override suspend fun completeText(prompt: String, grammar: String): String = withContext(Dispatchers.Default) {
        val e = engine ?: run { ensureLoaded(); engine!! }
        val conversation = e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                maxOutputToken = maxOutputToken,
                enableResponseFormat = true,
            ),
        )
        try {
            val response = conversation.sendMessage(
                text = prompt,
                responseFormat = ResponseFormat.json(grammarToJsonSchema(grammar)),
                maxOutputToken = maxOutputToken,
            )
            stripDoubleEncodedStringValues(
                response.contents.contents.joinToString("") { c -> (c as? Content.Text)?.text ?: "" },
            )
        } finally {
            conversation.close()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        private fun stripGbnfQuotes(token: String): String {
            val t = token.trim()
            return when {
                t.startsWith("\\\"") && t.endsWith("\\\"") && t.length >= 4 -> t.substring(2, t.length - 2)
                t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 -> t.substring(1, t.length - 1)
                else -> t
            }
        }

        internal fun stripDoubleEncodedStringValues(json: String): String =
            json.replace(Regex("(\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*)\"\\\\\"([^\\\\]*?)\\\\\"\"")) { m ->
                "${m.groupValues[1]}\"${m.groupValues[2]}\""
            }

        internal fun grammarToJsonSchema(grammar: String): String {
            val dateLine = grammar.lineSequence().firstOrNull { it.startsWith("date ::=") }
            val dateCandidates = dateLine
                ?.removePrefix("date ::=")
                ?.split("|")
                ?.map(::stripGbnfQuotes)
                ?.filter { it.isNotEmpty() }
                ?.toList()
                ?: emptyList()

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
