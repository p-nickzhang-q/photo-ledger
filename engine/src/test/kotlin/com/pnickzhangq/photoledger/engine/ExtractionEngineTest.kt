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
}
