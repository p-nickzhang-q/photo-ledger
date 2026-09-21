// 票 21：流水搜索纯函数测试（纯 JVM，无 Robolectric——只依赖 Entry/MonthTotal 数据类）。
package io.github.pnickzhangq.photoledger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EntrySearchTest {

    private fun entry(
        merchant: String = "",
        amountPaid: Double = 0.0,
        datePaid: String = "",
        category: String = "餐饮",
    ) = Entry(
        merchant = merchant,
        amountPaid = amountPaid,
        datePaid = datePaid,
        dateSource = "PAYMENT_TIME",
        orderStatus = "",
        category = category,
        photoPath = null,
        thumbPath = null,
    )

    private val entries = listOf(
        entry(merchant = "如意馄饨", amountPaid = 14.5, datePaid = "2026-08-18 12:30:00", category = "餐饮"),
        entry(merchant = "", amountPaid = 29.90, datePaid = "2026-09-18 08:00:00", category = "交通"),
        entry(merchant = "全家便利店", amountPaid = 120.0, datePaid = "2026-09-01", category = "日用"),
    )

    @Test
    fun `空查询原样返回全部`() {
        assertEquals(entries, filterEntries(entries, ""))
        assertEquals(entries, filterEntries(entries, "   "))
    }

    @Test
    fun `按商家关键词过滤`() {
        assertEquals(listOf("全家便利店"), filterEntries(entries, "全家").map { it.merchant })
    }

    @Test
    fun `按类别过滤`() {
        assertEquals(1, filterEntries(entries, "交通").size)
    }

    @Test
    fun `金额输入一位小数可命中两位小数存储值`() {
        assertEquals(29.90, filterEntries(entries, "29.9").single().amountPaid, 0.0)
    }

    @Test
    fun `日期片段可命中——月与日均可`() {
        assertEquals(2, filterEntries(entries, "2026-09").size)
        assertEquals(1, filterEntries(entries, "09-18").size)
    }

    @Test
    fun `无命中返回空列表`() {
        assertEquals(emptyList<Entry>(), filterEntries(entries, "不存在的词"))
    }

    @Test
    fun `搜索月合计按过滤结果重算——月份倒序`() {
        val filtered = filterEntries(entries, "2026-09")
        val totals = searchMonthTotals(filtered)
        assertEquals(listOf("2026-09"), totals.map { it.month })
        assertEquals(2, totals.single().count)
        assertEquals(149.90, totals.single().total, 0.001)
    }
}
