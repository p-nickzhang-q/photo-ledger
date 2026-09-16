package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OCR 后处理层测试。用例形态取自真实截图 OCR 输出（票 01 实验 + fixtures 快照）：
 * 日期碎片（「下单时间2026-09-10 11:19」+「09.10」角标、缺年份）、金额粘连（¥13.6、1010）、
 * 一图多单（两个订单卡片交错）。
 */
class OcrPostProcessorTest {

    // ---------- 日期规范化 ----------

    @Test
    fun `完整日期时间直接规范化`() {
        assertEquals("2026-09-10 11:19:00", OcrPostProcessor.normalizeDate("下单时间2026-09-10 11:19"))
        assertEquals("2026-09-10 20:15:33", OcrPostProcessor.normalizeDate("2026-09-10 20:15:33"))
    }

    @Test
    fun `只有日期没有时间时补零秒`() {
        assertEquals("2026-09-10 00:00:00", OcrPostProcessor.normalizeDate("下单时间 2026-09-10"))
    }

    @Test
    fun `OCR 碎片 MM点dd 缺年份时结合上下文年份补全`() {
        // 真实输出形态：主日期行 + 「09.10」角标行并存；角标往往缺年
        assertEquals(
            "2026-09-10 00:00:00",
            OcrPostProcessor.normalizeDate("09.10", contextYear = 2026),
        )
        assertEquals(
            "2026-09-03 00:00:00",
            OcrPostProcessor.normalizeDate("09.03", contextYear = 2026),
        )
    }

    @Test
    fun `角标粘连后续文字时仍能提取 MM点dd`() {
        // 真实形态（淘宝/闪购列表页）：「09.09丨共4件（含包装/配送费）」「09.07|含包装/配送费实付款￥16.5」
        assertEquals(
            "2026-09-09 00:00:00",
            OcrPostProcessor.normalizeDate("09.09丨共4件（含包装/配送费）", contextYear = 2026),
        )
        assertEquals(
            "2026-09-07 00:00:00",
            OcrPostProcessor.normalizeDate("09.07|含包装/配送费实付款￥16.5", contextYear = 2026),
        )
    }

    @Test
    fun `中文日期格式转换`() {
        assertEquals("2026-09-10 00:00:00", OcrPostProcessor.normalizeDate("2026年9月10日"))
        assertEquals("2026-09-10 11:19:00", OcrPostProcessor.normalizeDate("2026年9月10日 11:19"))
    }

    @Test
    fun `MM-dd 缺年份（横杠形态）补上下文年份`() {
        assertEquals("2026-09-10 00:00:00", OcrPostProcessor.normalizeDate("09-10", contextYear = 2026))
    }

    @Test
    fun `无法识别的日期返回 null 而非猜测`() {
        // 0909-09-09 类畸形：宁可 null 让模型看到「无日期」，不给它喂畸形值
        assertEquals(null, OcrPostProcessor.normalizeDate("0909-09-09", contextYear = 2026))
        assertEquals(null, OcrPostProcessor.normalizeDate("", contextYear = 2026))
    }

    // ---------- 金额规范化 ----------

    @Test
    fun `¥ 前缀粘连剥离`() {
        assertEquals(13.6, OcrPostProcessor.normalizeAmount("￥13.6"))
        assertEquals(13.6, OcrPostProcessor.normalizeAmount("¥13.6"))
        assertEquals(25.60, OcrPostProcessor.normalizeAmount("实付款￥25.60"))
    }

    @Test
    fun `全角数字与逗号千分位`() {
        assertEquals(1234.5, OcrPostProcessor.normalizeAmount("１，２３４．５"))
        assertEquals(1234.5, OcrPostProcessor.normalizeAmount("1,234.5"))
    }

    @Test
    fun `千分位误判校正`() {
        // 真实痛点：OCR 把「10.10」丢小数点成「1010」；把「35.00」识别成「3500」。
        // 规则：4 位纯整数且不含小数点 → 视为「百位分隔丢失」，按两位小数解读（订单金额常见区间）
        assertEquals(10.10, OcrPostProcessor.normalizeAmount("1010"))
        assertEquals(35.00, OcrPostProcessor.normalizeAmount("3500"))
        // 但正常 4 位金额（如 3500 元整）不受影响的前提是有小数点或更长的整数段
        assertEquals(3500.0, OcrPostProcessor.normalizeAmount("3500.00"))
        assertEquals(12345.0, OcrPostProcessor.normalizeAmount("12345"))
    }

    @Test
    fun `无数字内容返回 null`() {
        assertEquals(null, OcrPostProcessor.normalizeAmount("含包装/配送费"))
        assertEquals(null, OcrPostProcessor.normalizeAmount(""))
    }

    @Test
    fun `订单流水号长数字串不当作金额`() {
        // 真实痛点：OCR 把 28 位订单号当文本行，金额提取必须剪枝
        assertEquals(null, OcrPostProcessor.normalizeAmount("4500000358202609107869151816"))
        assertEquals(null, OcrPostProcessor.normalizeAmount("20260910110113130266232759351292"))
        assertEquals(null, OcrPostProcessor.normalizeAmount("1234567"))
    }

    @Test
    fun `金额取货币符号后的数字段——前置计数不污染`() {
        // 真实形态（票 07 真机）：「共N件」前缀的旧逻辑会把 4/5 当金额候选污染 prompt
        assertEquals(
            30.6,
            OcrPostProcessor.normalizeAmount("共4件(含包装/配送费）实付款￥30.6"),
        )
        assertEquals(
            33.6,
            OcrPostProcessor.normalizeAmount("共5件(含包装/配送费）实付款￥33.6"),
        )
        assertEquals(
            16.5,
            OcrPostProcessor.normalizeAmount("09.07|含包装/配送费实付款￥16.5"),
        )
    }

    @Test
    fun `货币段形近字符修复——OCR 把 30 认成 3U`() {
        // 真实形态（票 07 真机 logcat）：「买付款￥3U.6」→ 修复为 30.6，模型不再拿到乱码
        assertEquals(
            30.6,
            OcrPostProcessor.normalizeAmount("共4件(含包装/配送费）买付款￥3U.6"),
        )
        assertEquals(30.6, OcrPostProcessor.normalizeAmount("实付款￥3O.6"))
        assertEquals(12.34, OcrPostProcessor.normalizeAmount("￥l2.34"))
        // 形近修复只作用于货币符号后的段：无符号行里的 U 不被当 0
        assertEquals(null, OcrPostProcessor.normalizeAmount("共U件"))
    }

    // ---------- 行聚类：一图多单 ----------

    private fun line(text: String, y: Int, x: Int = 70) = OcrLine(
        text = text, score = 0.99f,
        box = listOf(x.toFloat(), y.toFloat(), (x + 100).toFloat(), y.toFloat(),
            (x + 100).toFloat(), (y + 40).toFloat(), x.toFloat(), (y + 40).toFloat()),
    )

    @Test
    fun `按实付款关键词切分多订单区块`() {
        // 真实形态：两个订单卡片，各有「实付款￥xx」行；行序按 y 排列
        val lines = listOf(
            line("如意馄饨·干拌面光福店", y = 800),
            line("商家备餐中", y = 900),
            line("含包装/配送费实付款￥13.6", y = 1000),
            line("华莱士·全鸡汉堡光福2店", y = 1400),
            line("商家备餐中", y = 1500),
            line("含包装/配送费实付款￥17.1", y = 1600),
        )
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].any { "如意馄饨" in it.text })
        assertTrue(blocks[1].any { "华莱士" in it.text })
        assertTrue(blocks[0].any { "13.6" in it.text })
    }

    @Test
    fun `单订单整图一个区块`() {
        val lines = listOf(line("顶足便利店", y = 300), line("实付款￥26", y = 900))
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].size)
    }

    @Test
    fun `区块内行按阅读顺序排列`() {
        val lines = listOf(
            line("第二行", y = 2000),
            line("第一行", y = 1000),
            line("第一行右", y = 1000, x = 500),
        )
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        val texts = blocks[0].map { it.text }
        assertEquals(listOf("第一行", "第一行右", "第二行"), texts)
    }

    // ---------- 端到端：OCR 行 → 结构化 prompt 素材 ----------

    @Test
    fun `结构化素材含规范化日期与金额提示`() {
        val lines = listOf(
            line("老王川菜馆", y = 800),
            line("下单时间2026-09-09 11:42", y = 900),
            line("含包装/配送费实付款￥23.8", y = 1000),
            line("已完成", y = 1100),
        )
        val material = OcrPostProcessor.buildStructuredMaterial(lines)
        assertTrue("2026-09-09 11:42:00" in material.normalizedDates || "2026-09-09 11:42" in material.normalizedDates)
        assertTrue(material.amounts.contains(23.8))
        assertTrue(material.blockText.isNotEmpty())
    }
}
