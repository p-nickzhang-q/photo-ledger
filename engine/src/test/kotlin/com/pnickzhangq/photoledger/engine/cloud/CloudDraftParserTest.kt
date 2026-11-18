package com.pnickzhangq.photoledger.engine.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudDraftParserTest {

    private val categories = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")

    @Test
    fun `解析多单与内容列表`() {
        val raw = """
            {"orders":[
              {"merchant":"瑞幸咖啡","content":["冰美式×2","可颂"],"amountPaid":29.9,"datePaid":"2026-11-18","category":"餐饮"},
              {"merchant":"","content":[],"amountPaid":"¥12.00","datePaid":"","category":"交通"}
            ]}
        """.trimIndent()

        val drafts = CloudDraftParser.parse(raw, categories)

        assertEquals(2, drafts.size)
        assertEquals("瑞幸咖啡", drafts[0].merchant)
        assertEquals("冰美式×2、可颂", drafts[0].content)
        assertEquals(29.9, drafts[0].amountPaid, 1e-9)
        assertEquals("2026-11-18", drafts[0].datePaid)
        assertEquals("餐饮", drafts[0].category)
        assertFalse(drafts[0].needsReview)
        assertEquals("", drafts[1].merchant)
        assertEquals("", drafts[1].content)
        assertEquals(12.0, drafts[1].amountPaid, 1e-9)
        assertEquals("", drafts[1].datePaid)
    }

    @Test
    fun `剥掉 markdown 围栏与前后解释`() {
        val raw = """
            好的，识别结果如下：
            ```json
            {"orders":[{"merchant":"全家便利店","content":["饭团×1"],"amountPaid":8.5,"datePaid":"2026-11-17","category":"餐饮"}]}
            ```
            请核对。
        """.trimIndent()

        val drafts = CloudDraftParser.parse(raw, categories)

        assertEquals(1, drafts.size)
        assertEquals("全家便利店", drafts[0].merchant)
        assertEquals("饭团×1", drafts[0].content)
    }

    @Test
    fun `裸数组与单对象根都能解析`() {
        val arrayRaw = """[{"amountPaid":10,"datePaid":"2026-11-01","category":"餐饮"}]"""
        val objectRaw = """{"merchant":"某店","amountPaid":20,"datePaid":"2026-11-02","category":"购物"}"""

        assertEquals(10.0, CloudDraftParser.parse(arrayRaw, categories).single().amountPaid, 1e-9)
        val single = CloudDraftParser.parse(objectRaw, categories).single()
        assertEquals("某店", single.merchant)
        assertEquals("购物", single.category)
    }

    @Test
    fun `类别不在清单落其他并标记复核`() {
        val raw = """{"orders":[{"merchant":"星巴克","amountPaid":38,"datePaid":"2026-11-18","category":"咖啡"}]}"""

        val draft = CloudDraftParser.parse(raw, categories).single()

        assertEquals("其他", draft.category)
        assertTrue(draft.needsReview)
    }

    @Test
    fun `中文与斜杠日期归一`() {
        fun dateOf(text: String): String =
            CloudDraftParser.parse(
                """{"orders":[{"amountPaid":1,"datePaid":"$text","category":"餐饮"}]}""",
                categories,
            ).single().datePaid

        assertEquals("2026-09-01", dateOf("2026年9月1日 10:00"))
        assertEquals("2026-11-08", dateOf("2026/11/8"))
        assertEquals("2026-11-08", dateOf("2026.11.8"))
        assertEquals("", dateOf("9月1日"))
    }

    @Test
    fun `金额非法订单被跳过且全部非法时抛错`() {
        val mixed = """
            {"orders":[
              {"merchant":"负数店","amountPaid":-5,"datePaid":"","category":"餐饮"},
              {"merchant":"正常店","amountPaid":15,"datePaid":"","category":"餐饮"}
            ]}
        """.trimIndent()
        val drafts = CloudDraftParser.parse(mixed, categories)
        assertEquals(1, drafts.size)
        assertEquals("正常店", drafts[0].merchant)

        try {
            CloudDraftParser.parse("""{"orders":[{"amountPaid":"abc","category":"餐饮"}]}""", categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudParseException) {
            assertTrue(e.message!!.contains("金额"))
        }
    }

    @Test
    fun `内容字符串拆分去重并限长`() {
        val raw = """{"orders":[{"amountPaid":1,"datePaid":"","category":"餐饮","content":"冰美式×2、可颂,拿铁；冰美式×2"}]}"""
        assertEquals(
            "冰美式×2、可颂、拿铁",
            CloudDraftParser.parse(raw, categories).single().content,
        )

        val longItem = "超".repeat(40)
        val capped = (1..12).joinToString("、") { if (it == 1) longItem else "商品$it" }
        val cappedRaw = """{"orders":[{"amountPaid":1,"datePaid":"","category":"餐饮","content":"$capped"}]}"""
        val items = CloudDraftParser.parse(cappedRaw, categories).single().content.split("、")
        assertEquals(10, items.size)
        assertEquals(30, items.first().length)
    }

    @Test
    fun `内容对象形态可取名称与数量`() {
        val raw = """
            {"orders":[{"amountPaid":9,
              "category":"餐饮",
              "content":[{"name":"拿铁","quantity":2},{"title":"可颂","qty":1}]
            }]}
        """.trimIndent()

        assertEquals("拿铁×2、可颂", CloudDraftParser.parse(raw, categories).single().content)
    }

    @Test
    fun `空 orders 抛错`() {
        try {
            CloudDraftParser.parse("""{"orders":[]}""", categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudParseException) {
            assertTrue(e.message!!.contains("订单"))
        }
    }
}
