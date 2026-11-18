package com.pnickzhangq.photoledger.engine.cloud

import com.pnickzhangq.photoledger.engine.DEFAULT_CATEGORIES
import kotlin.test.Test
import kotlin.test.assertTrue

class CloudPromptBuilderTest {

    @Test
    fun `prompt 含全部类别与口径反例`() {
        val prompt = CloudPromptBuilder.build(DEFAULT_CATEGORIES)
        DEFAULT_CATEGORIES.forEach { assertTrue(prompt.contains(it), "缺类别 $it") }
        assertTrue(prompt.contains("orders"))
        assertTrue(prompt.contains("实付款"))
        assertTrue(prompt.contains("不要取商品总价"))
        assertTrue(prompt.contains("付款时间"))
        assertTrue(prompt.contains("不要写价格"))
    }
}
