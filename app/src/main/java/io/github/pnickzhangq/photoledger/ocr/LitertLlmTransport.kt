// 票 13：LiteRT-LM 推理后端（速度破局评估）。
// LlmTransport 的第三实现：Engine/Conversation + ResponseFormat JSON Schema 约束
// （LLGuidance 后端）替代 GBNF。引擎核心零改动——grammar 参数（GBNF 文本）在此
// 转译为等价 JSON Schema：date 规则里的候选清单转 enum，其余按 Draft 契约重建。
package io.github.pnickzhangq.photoledger.ocr

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.pnickzhangq.photoledger.engine.GrammarGenerator
import com.pnickzhangq.photoledger.engine.LlmTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    // 票 25：会话池预建。Conversation 有状态（多轮 KV 缓存累积）且无 reset，
    // 跨图复用会污染上下文；改为「用后预建」——decode 结束后后台建下一个，
    // 把 ~2s 创建开销藏进下张图 OCR/解析期间。任意时刻至多一个会话在推理
    // （预建会话完成后才会被 acquire）。
    private val convScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val convMutex = Mutex()
    private var pooledConv: Deferred<Conversation>? = null

    /** 初始化（重操作，10s 级）——首次 completeText 惰性触发或调用方显式预加载。 */
    fun ensureLoaded() {
        if (engine != null) return
        val t0 = System.currentTimeMillis()
        // 票 25：Benchmark 自报（TTFT/prefill/decode 吞吐）需显式开关；
        // 只影响诊断日志，不改推理结果
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableBenchmark = true
        val e = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
            ),
        )
        e.initialize()
        engine = e
        Log.i(TAG, "SMOKE_LITERT_LOAD_OK ms=${System.currentTimeMillis() - t0} backend=${backend::class.simpleName}")
        prewarmConversation()
    }

    @OptIn(ExperimentalApi::class)
    private fun createConversation(e: Engine, source: String): Conversation {
        val t = System.currentTimeMillis()
        val c = e.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0), // 贪心，对齐 llama.cpp
                maxOutputToken = maxOutputToken,
                enableResponseFormat = true,
            ),
        )
        Log.i(TAG, "LITERT_CONV_CREATE ms=${System.currentTimeMillis() - t} source=$source")
        return c
    }

    /** 后台预建会话（fire-and-forget）；已有预建中的会话则跳过。失败静默，取用时回退同步创建。 */
    private fun prewarmConversation() {
        val e = engine ?: return
        convScope.launch {
            convMutex.withLock {
                if (pooledConv == null) {
                    pooledConv = try {
                        CompletableDeferred(createConversation(e, source = "prewarm"))
                    } catch (t: Throwable) {
                        Log.w(TAG, "LITERT_CONV_PREWARM_FAIL", t)
                        null
                    }
                }
            }
        }
    }

    /** 取会话：优先预建池（创建已完成后才被发布，等待零成本），无则同步创建。 */
    @OptIn(ExperimentalApi::class)
    private suspend fun acquireConversation(): Conversation {
        val d = convMutex.withLock { pooledConv }
        val pooled = if (d != null) {
            convMutex.withLock { pooledConv = null }
            runCatching { d.await() }.getOrNull()
        } else null
        return pooled ?: createConversation(engine!!, source = "sync")
    }

    override suspend fun complete(imageData: ByteArray, imageMime: String, prompt: String, grammar: String): String =
        throw UnsupportedOperationException("端侧 LiteRT 路线只走 OCR+文本（ADR-0004），不支持图像直入")

    @OptIn(ExperimentalApi::class)
    override suspend fun completeText(prompt: String, grammar: String): String = withContext(Dispatchers.Default) {
        val e = engine ?: run { ensureLoaded(); engine!! }
        val conversation = acquireConversation()
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
            // 票 23 诊断：TTFT/prefill/decode 吞吐（runtime 自报）——仅诊断用，
            // 未开 Benchmark 时 getBenchmarkInfo 会抛异常，绝不能影响提取结果（真机 11:23 事故）
            runCatching { Log.i(TAG, "LITERT_BENCH ${conversation.getBenchmarkInfo()}") }
            // 票 13 实测：LLGuidance 约束下 JSON Schema enum 字段输出双重编码
            // （"currency": "\"CNY\""），字符串字段值带一层字面引号。出口清洗：
            // 把 "…"（字面引号包裹的值）还原为裸值。自由字符串字段（merchant 等）
            // 输出正常不带双引号，不受影响；只剥「整体为 \"...\"」形态的值。
            stripDoubleEncodedStringValues(out)
        } finally {
            runCatching { conversation.close() }
            // 票 25：本会话已结束且关闭，立刻预建下一个，覆盖下张图的 OCR/解析窗口
            prewarmConversation()
        }
    }

    fun close() {
        // 进程级单例 transport：预建中的会话随进程/引擎回收，不做精细清理
        convScope.cancel()
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
