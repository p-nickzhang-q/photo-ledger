// 票 09：汇总查询 S3 测试（JVM + Room 内存库）。
// 锁行为：混合日期格式同月合并、月倒序、类别合计含手工记录、口径=amountPaid、空日期归 "" 组。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LedgerSummaryTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var photoRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = LedgerDatabase.inMemory(context)
        photoRoot = File(context.cacheDir, "summary-test-${System.nanoTime()}")
        repo = LedgerRepository(db, PhotoStore(photoRoot))
    }

    @After
    fun tearDown() {
        db.close()
        photoRoot.deleteRecursively()
    }

    /** 队列路径入账：date_paid 为 date-only（四字段契约后的格式），类别默认「其他」。 */
    private suspend fun confirmEntry(merchant: String, amount: Double, datePaid: String, category: String = "其他") =
        repo.confirm(
            Draft(
                merchant = merchant,
                amountPaid = amount,
                datePaid = datePaid,
                category = category,
            ),
        )

    @Test
    fun `混合日期格式同月合并`() = runTest {
        // 队列路径存 date-only；冒烟/手工路径存 datetime——两种格式必须归同一月
        confirmEntry("A", 10.5, "2026-09-01")
        repo.addManual(merchant = "B", amountPaid = 20.25, datePaid = "2026-09-15 08:30:00", category = "购物")
        val months = repo.monthTotals.first()
        assertEquals(1, months.size)
        assertEquals("2026-09", months[0].month)
        assertEquals(30.75, months[0].total, 0.001)
        assertEquals(2, months[0].count)
    }

    @Test
    fun `月度列表倒序且按月独立`() = runTest {
        confirmEntry("A", 10.0, "2026-08-20")
        confirmEntry("B", 30.0, "2026-09-05")
        confirmEntry("C", 20.0, "2026-09-01")
        val months = repo.monthTotals.first()
        assertEquals(listOf("2026-09", "2026-08"), months.map { it.month })
        assertEquals(50.0, months[0].total, 0.001)
        assertEquals(10.0, months[1].total, 0.001)
    }

    @Test
    fun `类别合计含手工记录且金额倒序`() = runTest {
        confirmEntry("提取A", 12.5, "2026-09-01") // 类别默认 "其他"（契约默认值）
        repo.addManual(merchant = "手工B", amountPaid = 30.0, datePaid = "2026-09-02", category = "餐饮")
        repo.addManual(merchant = "手工C", amountPaid = 7.5, datePaid = "2026-09-03", category = "餐饮")
        val totals = repo.categoryTotals("2026-09").first()
        assertEquals(listOf("餐饮", "其他"), totals.map { it.category })
        assertEquals(37.5, totals[0].total, 0.001)
        assertEquals(12.5, totals[1].total, 0.001)
    }

    @Test
    fun `类别合计限定当月——历史月份不混入`() = runTest {
        repo.addManual(merchant = "上月", amountPaid = 100.0, datePaid = "2026-08-15", category = "餐饮")
        confirmEntry("本月", 5.0, "2026-09-01")
        val totals = repo.categoryTotals("2026-09").first()
        assertEquals(1, totals.size)
        assertEquals(5.0, totals[0].total, 0.001)
    }

    @Test
    fun `空日期归空组且不进当月`() = runTest {
        // S3「手工空表单可成 Entry」：datePaid 可为空串
        repo.addManual(merchant = "", amountPaid = 3.0, datePaid = "", category = "其他")
        val months = repo.monthTotals.first()
        assertEquals(1, months.size)
        assertEquals("", months[0].month)
        assertEquals(3.0, months[0].total, 0.001)
        assertEquals(emptyList<CategoryTotal>(), repo.categoryTotals("2026-09").first())
    }

    @Test
    fun `口径只算实付款——同商家多笔独立累加`() = runTest {
        // orderStatus / dateSource 不参与统计
        confirmEntry("A", 10.0, "2026-09-01")
        confirmEntry("A", 10.0, "2026-09-02")
        assertEquals(20.0, repo.monthTotals.first().single().total, 0.001)
    }
}
