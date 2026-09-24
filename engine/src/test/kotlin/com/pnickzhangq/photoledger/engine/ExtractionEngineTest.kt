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
    fun `多单时各块各自匹配商户——同图不同单不得回填同一商家`() = runTest {
        // 票 27 真机 bug 回归：商户回填曾对整图行匹配一次，多单全部回填同一商户。
        // 两块分别含沙县小吃/蜜雪冰城，记忆按块内容返回不同命中。
        val transport = FakeTransport(
            """{"amountPaid":28.0,"datePaid":"2026-09-23","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val memories = listOf(
            MerchantAlias("沙县小吃", "沙县小吃", "餐饮"),
            MerchantAlias("蜜雪冰城", "蜜雪冰城", "餐饮"),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("闪购沙县小吃(光福店）", 0.99f, 0),
                ln("实付款￥28", 0.98f, 100),
                ln("蜜雪冰城（步行街店）", 0.99f, 1000),
                ln("实付款￥8", 0.98f, 1100),
            ),
            fallbackYear = 2026,
            merchantResolver = { block ->
                MerchantMatcher.matchDetail(block.map { it.text }, memories)
            },
        )
        assertEquals(2, drafts.size)
        assertEquals("沙县小吃", drafts[0].merchant)
        assertEquals("蜜雪冰城", drafts[1].merchant)
    }

    @Test
    fun `商户回填类别不在当前类别列表时不覆盖`() = runTest {
        val transport = FakeTransport(
            """{"amountPaid":8.0,"datePaid":"2026-09-23","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("蜜雪冰城（步行街店）", 0.99f, 0),
                ln("实付款￥8", 0.98f, 100),
            ),
            fallbackYear = 2026,
            // 记忆给的类别「饮品」不在引擎类别列表内 → 保留模型输出「餐饮」
            merchantResolver = { MerchantAlias("蜜雪冰城", "蜜雪冰城", "饮品") },
        )
        assertEquals("蜜雪冰城", drafts.single().merchant)
        assertEquals("餐饮", drafts.single().category)
    }

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
    fun `金额锚定兜底——模型抄走时间行数字时替换为唯一货币金额`() = runTest {
        // 真机事故（09-21）：极简支付成功页无「实付款」字样，0.6B 输出 amountPaid=20.0
        // （从「20:34 8」时间行抄的），唯一的 ¥51.60 被弃。OCR 行取自 logcat E2E_OCR_LINE。
        val transport = FakeTransport(
            """{"amountPaid":20.0,"datePaid":"","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("20:34 8", 0.91f, 0),
                ln("支付成功", 1.0f, 1),
                ln("格瑞思", 0.99f, 2),
                ln("¥51.60", 0.93f, 3),
                ln("完成", 1.0f, 4),
            ),
            fallbackYear = 2026,
        )
        assertEquals(1, drafts.size)
        assertEquals(51.6, drafts[0].amountPaid, 0.001, "应被锚定为唯一的货币符号金额")
        val prompt = transport.lastPrompt!!
        assertTrue("20" !in prompt.substringAfter("金额数字"), "时间行数字不应进金额参考清单：$prompt")
    }

    @Test
    fun `金额锚定兜底——支付宝账单详情页卡号污染时替换为负号金额`() = runTest {
        // 真机事故（09-21）：淘宝闪购账单详情，模型输出 12.12（卡号尾号 1212 膨胀产物），
        // 真实金额「-29.90」无货币符号但为负号强信号。OCR 行取自 logcat E2E_OCR_LINE。
        val transport = FakeTransport(
            """{"amountPaid":12.12,"datePaid":"2026-09-18","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("10:16", 1.0f, 0),
                ln("账单详情", 1.0f, 2),
                ln("闪购", 1.0f, 4),
                ln("淘宝闪购", 0.90f, 5),
                ln("-29.90", 0.96f, 6),
                ln("交易成功", 1.0f, 7),
                ln("支付时间", 1.0f, 8),
                ln("2026-09-18 11:40:51", 1.0f, 9),
                ln("付款方式", 1.0f, 10),
                ln("工商银行储蓄卡(1212）〉", 0.95f, 11),
                ln("商品说明", 1.0f, 12),
                ln("七里弄堂生煎(光福店)外卖订单", 1.0f, 13),
                ln("立即领取3积分", 1.0f, 15),
                ln("账单分类", 1.0f, 19),
                ln("饮美食〉", 0.89f, 20),
            ),
            fallbackYear = 2026,
        )
        assertEquals(1, drafts.size)
        assertEquals(29.9, drafts[0].amountPaid, 0.001, "应被锚定为负号强信号金额")
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
