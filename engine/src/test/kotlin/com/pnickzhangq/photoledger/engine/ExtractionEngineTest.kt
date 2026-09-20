package com.pnickzhangq.photoledger.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 用假 transport 测引擎门面的编排（spec Testing Decisions：S2-S4 测逻辑用假模型）。 */
class ExtractionEngineTest {

    private class FakeTransport(val response: String) : LlmTransport {
        var lastGrammar: String? = null
        var lastPrompt: String? = null

        override suspend fun complete(
            imageData: ByteArray,
            imageMime: String,
            prompt: String,
            grammar: String,
        ): String {
            lastGrammar = grammar
            lastPrompt = prompt
            return response
        }
    }

    private val categories = listOf("餐饮", "购物", "其他")

    @Test
    fun `编排 prompt-grammar-transport-解析`() = runTest {
        val transport = FakeTransport(
            """{"merchant":"美团","amountPaid":35.5,"currency":"CNY","datePaid":"2026-09-10 12:30:00","dateSource":"payment_time","orderStatus":"已完成","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, categories)
        val draft = engine.extract(ByteArray(10), "image/png")

        assertEquals("美团", draft.merchant)
        assertEquals("餐饮", draft.category)
        // 引擎把带类别枚举的 grammar 交给 transport（GBNF 里类别以 \"餐饮\" 字面量出现）
        assertTrue("\\\"餐饮\\\"" in transport.lastGrammar!!, "grammar 应含类别枚举：${transport.lastGrammar}")
        // prompt 带口径说明
        assertTrue("实付" in transport.lastPrompt!!)
    }

    @Test
    fun `解析失败向上抛 ExtractionParseException`() = runTest {
        val engine = ExtractionEngine(FakeTransport("不是 JSON"), categories)
        try {
            engine.extract(ByteArray(10), "image/png")
            throw AssertionError("应当抛 ExtractionParseException")
        } catch (e: ExtractionParseException) {
            // 预期路径
        }
    }

    @Test
    fun `空类别列表被拒绝`() {
        try {
            ExtractionEngine(FakeTransport(""), emptyList())
            throw AssertionError("应当抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期路径
        }
    }

    @Test
    fun `低置信行不进 prompt——状态栏碎片不再带偏金额`() = runTest {
        // 真机事故（370 元转账页提取成 794）：0.57 置信度的状态栏「794」被模型当成金额。
        // 重放差分验证：仅过滤该行，模型即取回 370。
        val transport = FakeTransport(
            """{"merchant":"郑怡","amountPaid":370.0,"currency":"CNY","datePaid":"2026-09-15","dateSource":"payment_time","orderStatus":"","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("10:41", 1.0f, 0),
                ln("794", 0.57f, 1),
                ln("-370.00", 0.94f, 2),
                ln("交易成功", 1.0f, 3),
                ln("2026-09-15 16:46:28", 0.98f, 4),
            ),
            fallbackYear = 2026,
        )
        assertEquals(1, drafts.size)
        assertEquals(370.0, drafts[0].amountPaid, 0.001)
        val prompt = transport.lastPrompt!!
        assertTrue("794" !in prompt, "低置信行不应出现在 prompt：$prompt")
        assertTrue("-370.00" in prompt)
        assertTrue("20.26" !in prompt, "日期行修复出的假金额不应进候选：$prompt")
    }
}
