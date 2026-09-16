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
import com.pnickzhangq.photoledger.engine.GrammarGenerator
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
                responseFormat = ResponseFormat.json(GrammarGenerator.toJsonSchema(grammar)),
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
        internal fun stripDoubleEncodedStringValues(json: String): String =
            json.replace(Regex("(\"(?:[^\"\\\\]|\\\\.)*\"\\s*:\\s*)\"\\\\\"([^\\\\]*?)\\\\\"\"")) { m ->
                "${m.groupValues[1]}\"${m.groupValues[2]}\""
            }
    }
}
