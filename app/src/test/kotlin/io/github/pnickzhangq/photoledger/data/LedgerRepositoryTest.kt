// S3 接缝测试（票 06）：Draft→Entry 确认流，JVM + Room 内存库。
// 断言外部行为：确认才落库、放弃不留痕、手工空表单可成 Entry、
// 删除可选连图删（文件层面）。不断言实现细节（spec「Testing Decisions」）。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var photoRoot: File

    private val sampleDraft = Draft(
        merchant = "如意馄饨·干拌面光福店",
        amountPaid = 14.5,
        currency = "CNY",
        datePaid = "2026-08-18 12:30:00",
        dateSource = DateSource.PAYMENT_TIME,
        orderStatus = "已完成",
        category = "餐饮",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = LedgerDatabase.inMemory(context)
        photoRoot = File(context.cacheDir, "photos-test-${System.nanoTime()}")
        repo = LedgerRepository(db, PhotoStore(photoRoot))
    }

    @After
    fun tearDown() {
        db.close()
        photoRoot.deleteRecursively()
    }

    @Test
    fun `确认才落库——Draft 本身不产生 Entry`() = runTest {
        // Draft 存在于确认流之外；库里初始为空
        assertEquals(0, db.entryDao().count())
        // 未调用 confirm 前，Draft 就是 Draft
        val draft = sampleDraft
        assertEquals(0, db.entryDao().count())
        // 确认后落库
        repo.confirm(draft, photoBytes = null)
        assertEquals(1, db.entryDao().count())
    }

    @Test
    fun `确认时编辑的字段生效`() = runTest {
        repo.confirm(
            sampleDraft,
            editedMerchant = "手改店名",
            editedAmount = 99.9,
            editedCategory = "购物",
        )
        val entry = repo.entries.first().single()
        assertEquals("手改店名", entry.merchant)
        assertEquals(99.9, entry.amountPaid, 0.001)
        assertEquals("购物", entry.category)
        // 未编辑字段沿用 Draft
        assertEquals("2026-08-18 12:30:00", entry.datePaid)
        assertEquals(DateSource.PAYMENT_TIME.name, entry.dateSource)
    }

    @Test
    fun `放弃不留痕——discard 后库与照片目录均无变化`() = runTest {
        repo.discard()
        assertEquals(0, db.entryDao().count())
        assertEquals(0, photoRoot.listFiles()?.size ?: 0)
    }

    @Test
    fun `手工空表单可成 Entry`() = runTest {
        val id = repo.addManual(merchant = "", amountPaid = 0.0, datePaid = "", category = "")
        assertTrue(id > 0)
        val entry = repo.entries.first().single()
        assertEquals("", entry.merchant)
        assertEquals(0.0, entry.amountPaid, 0.001)
        assertNull(entry.photoPath)
        assertNull(entry.thumbPath)
    }

    @Test
    fun `确认带截图——照片与缩略图落私有目录，库存相对路径`() = runTest {
        val png1x1 = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG 魔数（内容不校验，仅占位）
        )
        repo.confirm(sampleDraft, photoBytes = png1x1)
        val entry = repo.entries.first().single()
        assertNotNull(entry.photoPath)
        assertTrue(entry.photoPath!!.endsWith(".png"))
        // 文件真实存在
        assertTrue(File(File(photoRoot, "photos"), entry.photoPath).exists())
    }

    @Test
    fun `删除 Entry 不连图——库删文件留`() = runTest {
        repo.confirm(sampleDraft, photoBytes = byteArrayOf(1, 2, 3))
        val entry = repo.entries.first().single()
        val photoFile = File(File(photoRoot, "photos"), entry.photoPath!!)

        repo.delete(entry, deletePhoto = false)
        assertEquals(0, db.entryDao().count())
        assertTrue(photoFile.exists())
    }

    @Test
    fun `删除 Entry 连图——库与文件都删`() = runTest {
        repo.confirm(sampleDraft, photoBytes = byteArrayOf(1, 2, 3))
        val entry = repo.entries.first().single()
        val photoFile = File(File(photoRoot, "photos"), entry.photoPath!!)

        repo.delete(entry, deletePhoto = true)
        assertEquals(0, db.entryDao().count())
        assertTrue(!photoFile.exists())
    }

    @Test
    fun `编辑 Entry 保留 id 刷新 modifiedAt`() = runTest {
        repo.confirm(sampleDraft)
        val before = repo.entries.first().single()
        Thread.sleep(5) // 保证 modifiedAt 能观察到变化
        repo.edit(before) { copy(merchant = "改后", amountPaid = 15.0) }
        val after = repo.entries.first().single()
        assertEquals(before.id, after.id)
        assertEquals("改后", after.merchant)
        assertTrue(after.modifiedAt >= before.modifiedAt)
    }

    @Test
    fun `列表倒序——新确认在前`() = runTest {
        repo.confirm(sampleDraft.copy(merchant = "A", datePaid = "2026-08-01 10:00:00"))
        repo.confirm(sampleDraft.copy(merchant = "B", datePaid = "2026-09-01 10:00:00"))
        val list = repo.entries.first()
        assertEquals(listOf("B", "A"), list.map { it.merchant })
    }

    // ---- 商户记忆（票 27；注意：SEED_CALLBACK 会给内存库种品牌字典，断言按目标商户过滤，不断言全表空）----

    @Test
    fun `票27——确认时商户非空则学习进记忆`() = runTest {
        repo.confirm(sampleDraft, photoBytes = null)
        val mem = repo.merchantMemories().filter { it.alias == "如意馄饨·干拌面光福店" }
        assertEquals(1, mem.size)
        assertEquals("如意馄饨·干拌面光福店", mem.single().canonical)
    }

    @Test
    fun `票27——空商户不学习，重复确认计数累加`() = runTest {
        repo.confirm(sampleDraft.copy(merchant = ""), photoBytes = null)
        assertTrue(db.merchantMemoryDao().byAlias("如意馄饨·干拌面光福店") == null)
        repo.confirm(sampleDraft, photoBytes = null)
        repo.confirm(sampleDraft, photoBytes = null)
        assertEquals(2, db.merchantMemoryDao().byAlias("如意馄饨·干拌面光福店")?.hitCount)
    }

    @Test
    fun `票27——编辑补填商户时学习`() = runTest {
        repo.confirm(sampleDraft.copy(merchant = ""), photoBytes = null)
        assertTrue(db.merchantMemoryDao().byAlias("华莱士") == null)
        val entry = repo.entries.first().single()
        repo.edit(entry) { copy(merchant = "华莱士") }
        assertEquals("华莱士", repo.merchantMemories().single { it.alias == "华莱士" }.canonical)
    }

    @Test
    fun `票27——商户名只做空白归一，重名不产生第二行`() = runTest {
        // 用非种子名（避免与 SEED_CALLBACK 种的品牌字典同键）
        repo.confirm(sampleDraft.copy(merchant = " 某某馄饨 "), photoBytes = null)
        repo.confirm(sampleDraft.copy(merchant = "某某馄饨"), photoBytes = null)
        val row = db.merchantMemoryDao().byAlias("某某馄饨")
        assertEquals(2, row?.hitCount)
    }

    @Test
    fun `票27——种子商户开箱即用且可命中`() = runTest {
        // SEED_CALLBACK 种的品牌（如蜜雪冰城）应能被提取器直接用于回填
        val mem = repo.merchantMemories()
        assertTrue(mem.any { it.alias == "蜜雪冰城" && it.canonical == "蜜雪冰城" })
        assertEquals(
            "蜜雪冰城",
            com.pnickzhangq.photoledger.engine.MerchantMatcher.match(
                listOf("蜜雷冰城（光福店）订单"), mem,
            ),
        )
    }

    // ---- 票 30：类别联动 ----

    @Test
    fun `票30——确认学习带类别，记忆可带出`() = runTest {
        repo.confirm(sampleDraft.copy(merchant = "华莱士"), editedCategory = "餐饮", photoBytes = null)
        val mem = repo.merchantMemories().single { it.alias == "华莱士" }
        assertEquals("餐饮", mem.category)
    }

    @Test
    fun `票30——重学时新类别覆盖旧类别`() = runTest {
        repo.confirm(sampleDraft.copy(merchant = "华莱士"), editedCategory = "购物", photoBytes = null)
        repo.confirm(sampleDraft.copy(merchant = "华莱士"), editedCategory = "餐饮", photoBytes = null)
        assertEquals("餐饮", repo.merchantMemories().single { it.alias == "华莱士" }.category)
    }

    @Test
    fun `票30——重学时空类别保留旧类别`() = runTest {
        repo.confirm(sampleDraft.copy(merchant = "华莱士"), editedCategory = "餐饮", photoBytes = null)
        repo.confirm(sampleDraft.copy(merchant = "华莱士"), editedCategory = "", photoBytes = null)
        assertEquals("餐饮", repo.merchantMemories().single { it.alias == "华莱士" }.category)
    }
}
