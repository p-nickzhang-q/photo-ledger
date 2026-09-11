package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DraftNormalizerTest {

    private val categories = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")

    @Test
    fun `正常 JSON 解析为 Draft`() {
        val json = """
            {"merchant":"美团","amountPaid":35.5,"currency":"CNY",
             "datePaid":"2026-09-10 12:30:00","dateSource":"payment_time",
             "orderStatus":"已完成","category":"餐饮"}
        """.trimIndent()
        val draft = DraftNormalizer.parse(json, categories)
        assertEquals("美团", draft.merchant)
        assertEquals(35.5, draft.amountPaid)
        assertEquals("CNY", draft.currency)
        assertEquals("2026-09-10 12:30:00", draft.datePaid)
        assertEquals(DateSource.PAYMENT_TIME, draft.dateSource)
        assertEquals("已完成", draft.orderStatus)
        assertEquals("餐饮", draft.category)
    }

    @Test
    fun `金额整数也接受`() {
        val json = """{"merchant":"淘宝","amountPaid":199,"currency":"CNY","datePaid":"2026-09-01","dateSource":"order_time","orderStatus":"待收货","category":"购物"}"""
        val draft = DraftNormalizer.parse(json, categories)
        assertEquals(199.0, draft.amountPaid)
    }

    @Test
    fun `dateSource 只接受两个枚举值`() {
        val json = """{"merchant":"x","amountPaid":1,"currency":"CNY","datePaid":"2026-09-01","dateSource":"foo","orderStatus":"","category":"其他"}"""
        assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
    }

    @Test
    fun `类别不在用户列表中时拒绝`() {
        val json = """{"merchant":"x","amountPaid":1,"currency":"CNY","datePaid":"2026-09-01","dateSource":"payment_time","orderStatus":"","category":"交通费"}"""
        val e = assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
        assertEquals(true, e.message!!.contains("交通费"))
    }

    @Test
    fun `币种不在白名单时拒绝`() {
        val json = """{"merchant":"x","amountPaid":1,"currency":"RUB","datePaid":"2026-09-01","dateSource":"payment_time","orderStatus":"","category":"其他"}"""
        assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
    }

    @Test
    fun `金额为负时拒绝`() {
        val json = """{"merchant":"x","amountPaid":-1,"currency":"CNY","datePaid":"2026-09-01","dateSource":"payment_time","orderStatus":"","category":"其他"}"""
        assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
    }

    @Test
    fun `非 JSON 输入时抛 ExtractionParseException`() {
        assertFailsWith<ExtractionParseException> {
            DraftNormalizer.parse("好的，以下是提取结果：{...}", categories)
        }
    }

    @Test
    fun `缺少必需字段时抛错`() {
        val json = """{"merchant":"x","amountPaid":1,"currency":"CNY","datePaid":"2026-09-01","dateSource":"payment_time"}"""
        assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
    }

    @Test
    fun `日期字段宽松接受但保持原文`() {
        // 模型可能只给日期不给时间；normalizer 不改写字段内容，口径归一只做标注
        val json = """{"merchant":"x","amountPaid":1,"currency":"CNY","datePaid":"2026年9月1日","dateSource":"order_time","orderStatus":"","category":"其他"}"""
        val draft = DraftNormalizer.parse(json, categories)
        assertEquals("2026年9月1日", draft.datePaid)
    }
}
