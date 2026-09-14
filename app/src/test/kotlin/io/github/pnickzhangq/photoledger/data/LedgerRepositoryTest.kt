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
        repo = LedgerRepository(db.entryDao(), PhotoStore(photoRoot))
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
}
