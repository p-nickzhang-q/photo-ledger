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
        assertTrue("付款时间" in p && "order_time" in p && "payment_time" in p)
    }
}
