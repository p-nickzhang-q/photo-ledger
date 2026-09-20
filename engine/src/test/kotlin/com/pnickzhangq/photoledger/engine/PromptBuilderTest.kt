package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptBuilderTest {

    @Test
    fun `prompt 注入用户类别列表`() {
        val p = PromptBuilder.build(listOf("吃饭", "剁手"))
        assertTrue("吃饭" in p && "剁手" in p)
    }

    @Test
    fun `prompt 表达实付款口径`() {
        val p = PromptBuilder.build(listOf("其他"))
        assertTrue("实付款" in p || "实付" in p, "prompt 须写明实付款口径（CONTEXT.md）")
    }

    @Test
    fun `prompt 表达付款时间优先口径`() {
        val p = PromptBuilder.build(listOf("其他"))
        // 票 07 提速：dateSource 不再是输出字段，口径退阶逻辑仍在（付款时间优先，退下单时间）
        assertTrue("付款时间" in p && "下单时间" in p)
    }

    // ---------- OCR 版 prompt（票 12） ----------

    @Test
    fun `OCR 版 prompt 注入 OCR 文本与类别`() {
        val p = PromptBuilder.buildFromOcr(
            categories = listOf("餐饮", "购物"),
            ocrText = "老王川菜馆\n实付款￥23.8",
            normalizedDates = listOf("2026-09-09 11:42:00"),
            amounts = listOf(23.8),
        )
        assertTrue("老王川菜馆" in p)
        assertTrue("餐饮" in p && "购物" in p)
        assertTrue("2026-09-09 11:42:00" in p)
        assertTrue("23.8" in p)
    }

    @Test
    fun `OCR 版 prompt 不再要求商家——契约三字段`() {
        val p = PromptBuilder.buildFromOcr(listOf("其他"), "闪购\n华莱士", emptyList(), emptyList())
        // 2026-09：merchant 退出模型输出（0.6B 抄写中文店名太弱，真机连续出错）
        assertTrue("merchant" !in p && "店铺名" !in p, "prompt 不应再要求商家字段：$p")
    }

    @Test
    fun `OCR 版 prompt 保持实付款口径`() {
        val p = PromptBuilder.buildFromOcr(listOf("其他"), "实付款￥1", emptyList(), emptyList())
        assertTrue("实付" in p, "prompt 须写明实付款口径（CONTEXT.md）")
    }
}
