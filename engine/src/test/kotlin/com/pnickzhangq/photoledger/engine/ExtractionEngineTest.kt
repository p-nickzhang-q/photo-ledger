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
        var calls = 0

        override suspend fun complete(
            imageData: ByteArray,
            imageMime: String,
            prompt: String,
            grammar: String,
        ): String {
            lastGrammar = grammar
            lastPrompt = prompt
            calls++
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

    // ---- 票 29：规则快路径 ----

    @Test
    fun `快路径——实付强锚加唯一日期加品牌类别命中时跳过LLM`() = runTest {
        val transport = FakeTransport("""{"amountPaid":0.0,"datePaid":"","category":"其他"}""")
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("蜜雪冰城（步行街店）", 0.99f, 0),
                ln("下单时间2026-09-23 11:12", 0.99f, 50),
                ln("实付款￥8.0", 0.98f, 100),
            ),
            fallbackYear = 2026,
            fastPath = true,
        )
        assertEquals(0, transport.calls, "快路径命中不应调用 LLM")
        val d = drafts.single()
        assertEquals(8.0, d.amountPaid, 0.001)
        assertEquals("2026-09-23", d.datePaid)
        assertEquals("餐饮", d.category, "品牌字典命中 → 类别")
    }

    @Test
    fun `快路径——多候选分差不足或双日期或无品牌时降级LLM`() = runTest {
        val transport = FakeTransport("""{"amountPaid":28.0,"datePaid":"2026-09-23","category":"餐饮"}""")
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        // 无品牌（格瑞思不在字典）→ 降级
        val degraded = engine.extractFromOcr(
            listOf(
                ln("格瑞思", 0.99f, 0),
                ln("下单时间2026-09-23 11:12", 0.99f, 50),
                ln("实付款￥28", 0.98f, 100),
            ),
            fallbackYear = 2026,
            fastPath = true,
        )
        assertEquals(1, transport.calls)
        assertEquals(28.0, degraded.single().amountPaid, 0.001, "格瑞思无品牌命中，应走LLM")

        // 双强锚同块（合计+实付款，分差 0.5 < 20）→ 降级
        val degraded2 = engine.extractFromOcr(
            listOf(
                ln("蜜雪冰城", 0.99f, 0),
                ln("合计￥27.5", 0.98f, 50),
                ln("实付款￥28", 0.98f, 100),
            ),
            fallbackYear = 2026,
            fastPath = true,
        )
        assertEquals(2, transport.calls)
        assertEquals(1, degraded2.size)
        assertEquals(28.0, degraded2.single().amountPaid, 0.001)
    }

    @Test
    fun `快路径——唯一货币金额的极简页可走快路径`() = runTest {
        // 票 28 真机形态：极简支付成功页只有 ¥51.60，无实付字样——单候选即可判
        val transport = FakeTransport("""{"amountPaid":0.0,"datePaid":"","category":"其他"}""")
        val engine = ExtractionEngine(transport, categories)
        fun ln(text: String, score: Float, y: Int) = OcrLine(
            text = text, score = score,
            box = listOf(0f, y.toFloat(), 200f, y.toFloat(), 200f, (y + 40).toFloat(), 0f, (y + 40).toFloat()),
        )
        val drafts = engine.extractFromOcr(
            listOf(
                ln("蜜雪冰城", 0.99f, 0),
                ln("¥51.60", 0.98f, 100),
            ),
            fallbackYear = 2026,
            fastPath = true,
        )
        assertEquals(0, transport.calls)
        assertEquals(51.6, drafts.single().amountPaid, 0.001)
        assertEquals("", drafts.single().datePaid, "无日期候选 → 空串（导入时兜底）")
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

    // ---- 票 32：列表页 UI 条带不得进商户匹配 ----

    @Test
    fun `列表页标签栏不得命中商户——真机飞猪误命中回归`() = runTest {
        // 真机事故（2026-09-28 11:13 淘宝闪购订单列表，两单）：第一单（如意馄饨）
        // 被回填成「飞猪」——顶部标签栏「飞猪旅行」被 contains 命中（种子 飞猪，
        // 同长度先到先得压过后学习的「闪购」）；真商户行「闪购YY」(0.74) 又被
        // 置信度阈值滤掉。OCR 行为 App 同款引擎全量转储（--ocrdebug）。
        val transport = FakeTransport(
            """{"amountPaid":16.0,"datePaid":"2026-09-28","category":"餐饮"}""",
        )
        val engine = ExtractionEngine(transport, DEFAULT_CATEGORIES)
        fun ln(text: String, score: Float, x1: Int, y1: Int, x2: Int, y2: Int) = OcrLine(
            text = text, score = score,
            box = listOf(
                x1.toFloat(), y1.toFloat(), x2.toFloat(), y1.toFloat(),
                x2.toFloat(), y2.toFloat(), x1.toFloat(), y2.toFloat(),
            ),
        )
        val lines = listOf(
            ln("11:13", 0.99f, 56, 34, 211, 81),
            ln("565", 0.71f, 595, 30, 1025, 87),
            ln("A", 0.33f, 669, 134, 721, 184),
            ln("品", 0.92f, 888, 128, 942, 185),
            ln("Q搜索订单", 0.99f, 174, 151, 396, 206),
            ln("A", 0.23f, 59, 164, 86, 194),
            ln("AI助手", 0.96f, 642, 187, 745, 233),
            ln("筛选", 1.00f, 766, 189, 839, 231),
            ln("管理", 1.00f, 879, 189, 951, 231),
            ln("全部订单", 1.00f, 40, 273, 227, 332),
            ln("购物", 1.00f, 283, 275, 382, 330),
            ln("闪购外卖", 1.00f, 439, 274, 618, 328),
            ln("飞猪旅行", 1.00f, 677, 274, 851, 328),
            ln("全部", 1.00f, 77, 392, 168, 441),
            ln("待发货", 1.00f, 290, 390, 417, 442),
            ln("待收货", 1.00f, 498, 390, 622, 442),
            ln("待评价", 1.00f, 700, 390, 825, 442),
            ln("已关闭", 1.00f, 911, 391, 1032, 442),
            ln("闪购YY", 0.74f, 47, 482, 208, 535),
            ln("如意馄饨·干拌面····光福店〉商家备餐中", 0.92f, 228, 483, 1042, 540),
            ln("如意馄饨", 0.98f, 48, 576, 177, 617),
            ln("如意馄饨荠菜鲜肉大馄饨10个-默认", 0.99f, 334, 576, 974, 621),
            ln("¥16", 0.91f, 950, 575, 1040, 620),
            ln("默认", 1.00f, 332, 642, 413, 690),
            ln("x1", 0.73f, 996, 649, 1039, 686),
            ln("8预计11:32-11:47送达", 0.96f, 332, 722, 740, 773),
            ln("吃饱吃好", 1.00f, 46, 766, 304, 839),
            ln("下单时间2026-09-2811:13", 0.99f, 389, 779, 828, 823),
            ln("09.28", 1.00f, 460, 877, 565, 923),
            ln("含包装/配送费实付款￥16", 0.97f, 590, 870, 1042, 927),
            ln("联系商家", 1.00f, 602, 977, 765, 1030),
            ln("查看订单", 1.00f, 847, 977, 1005, 1030),
            ln("闪购YY悸动苏州光福镇店>", 0.85f, 50, 1135, 608, 1185),
            ln("商家备餐中", 1.00f, 833, 1138, 1039, 1187),
            ln("双皮奶芋圆一号-默认", 0.99f, 334, 1227, 688, 1271),
            ln("￥15.6", 0.90f, 918, 1223, 1045, 1275),
            ln("默认", 1.00f, 332, 1292, 413, 1340),
            ln("x1", 0.80f, 996, 1298, 1042, 1337),
            ln("预计11:32-11:47送达", 1.00f, 332, 1372, 740, 1423),
            ln("下单时间2026-09-2811:13", 0.99f, 392, 1429, 828, 1473),
            ln("09.28", 1.00f, 428, 1528, 534, 1569),
            ln("膜", 0.07f, 541, 1538, 562, 1557),
            ln("含包装/配送费实付款￥15.6", 0.98f, 558, 1520, 1047, 1577),
            ln("联系商家", 1.00f, 602, 1627, 765, 1680),
            ln("查看订单", 1.00f, 847, 1627, 1005, 1680),
            ln("营养细化好吸收", 0.98f, 648, 2043, 769, 2084),
            ln("低指型无乳糖牛奶", 0.78f, 650, 2093, 737, 2127),
            ln("【买12桶发12桶】牛肉板面", 1.00f, 63, 2305, 502, 2362),
            ln("伊利舒化无乳糖低脂牛奶22", 1.00f, 572, 2307, 1033, 2358),
            ln("6", 0.98f, 75, 2375, 100, 2395),
            ln(".29全网热销9000-", 0.93f, 100, 2373, 400, 2399),
            ln("51.26全网热销30万", 0.99f, 594, 2366, 931, 2399),
        )
        // 真机记忆表 = 内置种子 + 用户学习过的「闪购」（按 DB 顺序：种子在先）
        val memories = DEFAULT_MERCHANT_MEMORY.map { (k, v) -> MerchantAlias(k, k, v) } +
            MerchantAlias("闪购", "闪购", "餐饮")
        val drafts = engine.extractFromOcr(
            lines,
            fallbackYear = 2026,
            merchantResolver = { block -> MerchantMatcher.matchDetail(block.map { it.text }, memories) },
            fastPath = true,
        )
        assertEquals(2, drafts.size)
        assertTrue(drafts.none { it.merchant == "飞猪" }, "标签栏「飞猪旅行」不得命中商户：${drafts.map { it.merchant }}")
        assertEquals("闪购", drafts[0].merchant, "第一单应命中本单行内的「闪购」")
        assertEquals("闪购", drafts[1].merchant)
    }
}
