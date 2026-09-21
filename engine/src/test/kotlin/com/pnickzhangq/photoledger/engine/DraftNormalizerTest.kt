package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DraftNormalizerTest {

    private val categories = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")

    @Test
    fun `四字段契约 JSON 解析为 Draft 且默认值齐全`() {
        // 票 07 提速：模型只输出 4 个用户字段，currency/dateSource/orderStatus 由默认值承接
        val json = """{"merchant":"美团","amountPaid":35.5,"datePaid":"2026-09-10","category":"餐饮"}"""
        val draft = DraftNormalizer.parse(json, categories)
        assertEquals("美团", draft.merchant)
        assertEquals(35.5, draft.amountPaid)
        assertEquals("CNY", draft.currency)
        assertEquals("2026-09-10", draft.datePaid)
        assertEquals(DateSource.PAYMENT_TIME, draft.dateSource)
        assertEquals("", draft.orderStatus)
        assertEquals("餐饮", draft.category)
    }

    @Test
    fun `遗留七字段输出仍可解析——未知键容忍`() {
        // 兼容旧模型输出/测试夹具：多出的键忽略，出现过的契约键照常读取
        val json = """
            {"merchant":"淘宝","amountPaid":199,"currency":"CNY",
             "datePaid":"2026-09-01","dateSource":"order_time",
             "orderStatus":"待收货","category":"购物"}
        """.trimIndent()
        val draft = DraftNormalizer.parse(json, categories)
        assertEquals(199.0, draft.amountPaid)
        assertEquals("CNY", draft.currency)
        assertEquals("", draft.orderStatus) // 契约收紧后不再读旧键，口径归一走默认值
    }

    @Test
    fun `类别不在用户列表中时拒绝`() {
        val json = """{"merchant":"x","amountPaid":1,"datePaid":"2026-09-01","category":"交通费"}"""
        val e = assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
        assertEquals(true, e.message!!.contains("交通费"))
    }

    @Test
    fun `金额为负时拒绝`() {
        val json = """{"merchant":"x","amountPaid":-1,"datePaid":"2026-09-01","category":"其他"}"""
        assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
    }

    @Test
    fun `金额超出合理上限时拒绝——模型抄状态栏数字生成天文数字`() {
        // 真机事故（09-21）：模型把状态栏「89」抄成 200 多位零，JSON 撑爆 token 截断
        val json = """{"merchant":"x","amountPaid":8.9E298,"datePaid":"2026-09-21","category":"其他"}"""
        val e = assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
        assertEquals(true, e.message!!.contains("合理范围"))
    }

    @Test
    fun `非 JSON 输入时抛 ExtractionParseException`() {
        assertFailsWith<ExtractionParseException> {
            DraftNormalizer.parse("好的，以下是提取结果：{...}", categories)
        }
    }

    @Test
    fun `缺少必需字段时抛错`() {
        val json = """{"merchant":"x","amountPaid":1}"""
        assertFailsWith<ExtractionParseException> { DraftNormalizer.parse(json, categories) }
    }

    @Test
    fun `日期字段宽松接受但保持原文`() {
        // normalizer 不校验日期格式（合法性由约束解码的候选清单保证），不改写字段内容
        val json = """{"merchant":"x","amountPaid":1,"datePaid":"2026年9月1日","category":"其他"}"""
        val draft = DraftNormalizer.parse(json, categories)
        assertEquals("2026年9月1日", draft.datePaid)
    }
}
