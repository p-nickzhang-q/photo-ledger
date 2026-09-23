package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/** 票 27：商户种子质量约束——类别必须在内置八类内、别名可用（≥2 字）。 */
class DefaultMerchantsTest {

    @Test
    fun `种子非空且类别都在内置八类内`() {
        assertTrue(DEFAULT_MERCHANT_MEMORY.isNotEmpty())
        DEFAULT_MERCHANT_MEMORY.forEach { (alias, category) ->
            assertTrue(category in DEFAULT_CATEGORIES, "「$alias」类别「$category」不在内置八类")
        }
    }

    @Test
    fun `别名至少两字且无空白`() {
        DEFAULT_MERCHANT_MEMORY.forEach { (alias, _) ->
            assertTrue(alias.length >= 2, "「$alias」过短（模糊匹配禁用区）")
            assertTrue(alias.trim() == alias && !alias.contains(" "), "「$alias」含空白")
        }
    }
}
