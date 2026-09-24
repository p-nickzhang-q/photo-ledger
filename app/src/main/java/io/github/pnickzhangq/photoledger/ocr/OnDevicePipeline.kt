// 票 05：端侧端到端提取管线——截图 → OCR → 后处理 → 0.6B+GBNF → Draft 列表。
// 桥接 app 的 OcrEngine 输出到共享引擎的 OcrLine（类型同构直接转换），
// 并分段计时（OCR/后处理/LLM）供 Comments 记录与桌面对照。
package io.github.pnickzhangq.photoledger.ocr

import com.pnickzhangq.photoledger.engine.Draft
import com.pnickzhangq.photoledger.engine.ExtractionEngine
import com.pnickzhangq.photoledger.engine.LlmTransport
import com.pnickzhangq.photoledger.engine.MerchantAlias
import com.pnickzhangq.photoledger.engine.MerchantMatcher

/** 一段计时的分段结果。 */
data class StageTimes(
    val ocrMs: Long,
    val postMs: Long,
    val llmMs: Long,
) {
    val totalMs: Long get() = ocrMs + postMs + llmMs
}

/** 提取结果：成功（Draft 列表）或失败（带用户可读原因，上屏呈现而非静默）。 */
sealed class ExtractResult {
    data class Success(val drafts: List<Draft>, val times: StageTimes) : ExtractResult()
    data class Failure(val reason: String) : ExtractResult()
}

/** RAM 门槛（票 05 验收）：低于此值明确报错而非进低内存杀手范围。 */
class LowMemoryException(val availableMb: Long) :
    RuntimeException("可用内存不足（${availableMb}MB < 6144MB 门槛），无法安全加载 0.6B 模型")

class OnDevicePipeline(
    private val ocrEngine: OcrEngine,
    transport: LlmTransport,
    /** 当前类别列表（票 30：记忆回填类别时校验有效性；票 08 逐张实时取）。 */
    private val categories: List<String>,
    /** 票 27：商户记忆提供方（App 接 repo；默认空 = 不回填，CLI/测试无感）。 */
    private val merchantMemory: suspend () -> List<MerchantAlias> = { emptyList() },
) {
    private val engine = ExtractionEngine(transport, categories)

    /**
     * 全链路提取。分段计时；OCR 空结果/模型输出异常等均转 [ExtractResult.Failure]。
     * 内存检查在调用方（Activity 持有模型生命周期，进入前先 gate）。
     */
    suspend fun extract(
        pixels: IntArray,
        width: Int,
        height: Int,
        fallbackYear: Int,
    ): ExtractResult {
        // ---- OCR ----
        val t0 = System.currentTimeMillis()
        val lines = try {
            ocrEngine.run(pixels, width, height)
        } catch (t: Throwable) {
            return ExtractResult.Failure("OCR 失败：${t.message}")
        }
        val t1 = System.currentTimeMillis()
        android.util.Log.i(
            "OnDevicePipeline",
            "E2E_OCR lines=${lines.size} ms=${t1 - t0}\n" +
                lines.withIndex().joinToString("\n") { (i, l) ->
                    "E2E_OCR_LINE[$i] [${"%.2f".format(l.score)}] ${l.text}"
                },
        )
        if (lines.isEmpty()) {
            return ExtractResult.Failure("未识别到文本——可能不是订单截图")
        }

        // ---- 共享引擎：后处理 + prompt + GBNF + 解析（桌面同款）----
        return try {
            val drafts = engine.extractFromOcr(lines.toEngineLines(), fallbackYear)
            val t2 = System.currentTimeMillis()
            val postMs = 0L  // 后处理在 extractFromOcr 内部，计入 LLM 段
            // 票 23：LLM 段耗时单列——队列路径原来只有总量，10s 体感无法定位是 OCR 还是解码
            android.util.Log.i("OnDevicePipeline", "E2E_LLM ms=${t2 - t1} drafts=${drafts.size}")
            if (drafts.isEmpty()) {
                ExtractResult.Failure("识别到 ${lines.size} 行文本，但未找到订单块（无「实付款」特征）")
            } else {
                ExtractResult.Success(fillMerchants(drafts, lines), StageTimes(t1 - t0, postMs, t2 - t1))
            }
        } catch (t: Throwable) {
            ExtractResult.Failure("提取失败：${t.message}")
        }
    }

    /**
     * 票 27：模型不输出 merchant，空商户草稿用商户记忆本地匹配回填（命中 = 用户
     * 确认过的商户，复核页可见可改；未命中维持留空）。记忆读取失败不阻断提取。
     * 票 30：命中且记忆类别在当前类别列表内（用户可能删过类）→ 类别一并联动回填。
     */
    private suspend fun fillMerchants(
        drafts: List<Draft>,
        lines: List<OcrLine>,
    ): List<Draft> {
        if (drafts.none { it.merchant.isBlank() }) return drafts
        val aliases = try {
            merchantMemory()
        } catch (t: Throwable) {
            android.util.Log.w("OnDevicePipeline", "MERCHANT_MEMORY_READ_FAIL", t)
            return drafts
        }
        if (aliases.isEmpty()) return drafts
        val texts = lines.map { it.text }
        val validCategories = categories.toSet()
        return drafts.map { d ->
            if (d.merchant.isNotBlank()) return@map d
            val hit = MerchantMatcher.matchDetail(texts, aliases) ?: return@map d
            val category = hit.category?.takeIf { it in validCategories }
            d.copy(merchant = hit.canonical, category = category ?: d.category)
        }
    }
}

/** app OcrLine（box 四点嵌套）→ 引擎 OcrLine（box 扁平 [x1,y1,...]）——协议同构。 */
private fun OcrLine.toEngineLine() = com.pnickzhangq.photoledger.engine.OcrLine(
    text = text,
    score = score,
    box = listOf(
        box[0][0], box[0][1],
        box[1][0], box[1][1],
        box[2][0], box[2][1],
        box[3][0], box[3][1],
    ),
)

private fun List<OcrLine>.toEngineLines() = map { it.toEngineLine() }
