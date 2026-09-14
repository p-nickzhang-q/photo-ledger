// 票 05：LlmTransport 的 Android JNI 进程内实现。
// 包装票 04 的 LlamaNative.complete——引擎（ExtractionEngine）代码零改动复用，
// prompt/grammar/解析/口径归一与桌面完全同源（零分叉验收项）。
package io.github.pnickzhangq.photoledger.ocr

import com.pnickzhangq.photoledger.engine.LlmTransport

/**
 * @param maxTokens OCR 版 prompt 约 700 token + Draft JSON 输出约 60 token，
 *   1024 上限覆盖多行素材；GBNF 约束下不会跑飞。
 */
class JniLlmTransport(
    private val ctxProvider: () -> Long,
    private val maxTokens: Int = 1024,
) : LlmTransport {

    /** OCR 路线专用（票 12 起的主路线）。 */
    override suspend fun completeText(prompt: String, grammar: String): String {
        val ctx = ctxProvider()
        require(ctx != 0L) { "LLM 未加载（ctx=0），先加载 GGUF" }
        return LlamaNative.complete(
            ctx = ctx,
            prompt = prompt,
            grammar = grammar,
            nLen = maxTokens,
        )
    }

    /** VLM 路线在端侧不可行（ADR-0004），显式拒绝而非静默降级。 */
    override suspend fun complete(
        imageData: ByteArray,
        imageMime: String,
        prompt: String,
        grammar: String,
    ): String = throw UnsupportedOperationException(
        "端侧不支持 VLM 路线（ADR-0004）：请走 OCR 路线 extractFromOcr"
    )
}
