// 票 22：汇总统计纯函数测试（纯 JVM）。
package io.github.pnickzhangq.photoledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryStatsTest {

    private fun entry(
        merchant: String = "",
        amountPaid: Double = 0.0,
        datePaid: String = "2026-09-01",
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

    // ---- 环比 ----

    @Test
    fun `环比——涨跌百分比方向正确`() {
        assertEquals(25.0, monthOverMonth(125.0, 100.0).pctChange!!, 0.001)
        assertEquals(-20.0, monthOverMonth(80.0, 100.0).pctChange!!, 0.001)
    }

    @Test
    fun `环比——上月无数据或为零时 pctChange 为 null`() {
        assertNull(monthOverMonth(100.0, null).pctChange)
        assertNull(monthOverMonth(100.0, 0.0).pctChange)
    }

    // ---- 单笔画像 ----

    @Test
    fun `单笔画像——平均与最高`() {
        val stats = monthStats(
            listOf(
                entry(amountPaid = 14.5),
                entry(amountPaid = 51.6, category = "餐饮"),
                entry(amountPaid = 33.0),
            ),
        )!!
        assertEquals(33.03, stats.avgAmount, 0.01)
        assertEquals(51.6, stats.maxAmount, 0.0)
    }

    @Test
    fun `单笔标注——商家空回退类别`() {
        val stats = monthStats(listOf(entry(amountPaid = 51.6, category = "交通")))!!
        assertEquals("交通", stats.maxLabel)
        val labeled = monthStats(listOf(entry(merchant = "全家", amountPaid = 51.6)))!!
        assertEquals("全家", labeled.maxLabel)
    }

    @Test
    fun `空月返回 null`() {
        assertNull(monthStats(emptyList()))
    }
}
