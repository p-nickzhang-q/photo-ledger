package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 票 27：商户记忆匹配测试。用例取自真实截图 OCR 行形态（含 UI 痕迹与 OCR 误读）。 */
class MerchantMatcherTest {

    private val aliases = listOf(
        MerchantAlias("蜜雪冰城", "蜜雪冰城"),
        MerchantAlias("沙县小吃", "沙县小吃"),
        MerchantAlias("乡村基·川菜小炒", "乡村基"),
        MerchantAlias("美团", "美团"),
    )

    @Test
    fun `contains 命中回填别名`() {
        val lines = listOf("11:38", "闪购沙县小吃(光福店）>", "总优惠￥2.8实付￥28")
        assertEquals("沙县小吃", MerchantMatcher.match(lines, aliases))
    }

    @Test
    fun `多命中取最长别名`() {
        // 「乡村基·川菜小炒」比短别名更具体；另一行含「美团」
        val lines = listOf("美团外卖", "乡村基·川菜小炒")
        val richer = aliases + MerchantAlias("乡村基", "乡村基")
        assertEquals("乡村基", MerchantMatcher.match(lines, richer))
    }

    @Test
    fun `OCR 单字误读经模糊窗口命中`() {
        // 真机形态：雪→雷 单字误读，且行内粘连前后文
        assertEquals("蜜雪冰城", MerchantMatcher.match(listOf("订单详情 蜜雷冰城（光福店）"), aliases))
    }

    @Test
    fun `两字短别名不做模糊匹配——防误撞`() {
        // 「美图」与「美团」距离 1，但两字别名禁用模糊，宁缺勿错
        assertNull(MerchantMatcher.match(listOf("美图秀秀"), aliases))
    }

    @Test
    fun `无关行不误匹配`() {
        assertNull(MerchantMatcher.match(listOf("总优惠￥2.8实付￥28", "订单号80808362127"), aliases))
        assertNull(MerchantMatcher.match(emptyList(), aliases))
        assertNull(MerchantMatcher.match(listOf("实付款￥33.03"), emptyList()))
    }

    @Test
    fun `等长整行单字误读也命中`() {
        assertEquals("蜜雪冰城", MerchantMatcher.match(listOf("蜜雷冰城"), aliases))
    }

    @Test
    fun `含数字别名不做模糊匹配——真机 711 误命中日期行回归`() {
        // 票 29 回归：日期行「下单时间2026-09-23 11:12」的「311」曾被窗口模糊当成
        // 「711」的单字误读，格瑞思订单被回填成 711
        val seven = listOf(MerchantAlias("711", "711", "购物"))
        assertEquals(null, MerchantMatcher.match(listOf("下单时间2026-09-23 11:12"), seven))
        assertEquals("711", MerchantMatcher.match(listOf("711便利店"), seven), "contains 精确命中不受影响")
    }

    // ---- 票 30：类别联动 ----

    private val withCategory = listOf(
        MerchantAlias("沙县小吃", "沙县小吃", "餐饮"),
        MerchantAlias("蜜雪冰城", "蜜雪冰城", "餐饮"),
    )

    @Test
    fun `matchDetail 命中返回类别`() {
        val hit = MerchantMatcher.matchDetail(listOf("闪购沙县小吃(光福店）"), withCategory)
        assertEquals("沙县小吃", hit?.canonical)
        assertEquals("餐饮", hit?.category)
        // match() 兼容入口仍只给 canonical
        assertEquals("沙县小吃", MerchantMatcher.match(listOf("闪购沙县小吃(光福店）"), withCategory))
    }

    @Test
    fun `matchDetail 模糊命中也带类别`() {
        val hit = MerchantMatcher.matchDetail(listOf("蜜雷冰城（光福店）"), withCategory)
        assertEquals("蜜雪冰城", hit?.canonical)
        assertEquals("餐饮", hit?.category)
    }

    @Test
    fun `matchDetail 未命中返回 null`() {
        assertEquals(null, MerchantMatcher.matchDetail(listOf("总优惠￥2.8实付￥28"), withCategory))
    }
}
