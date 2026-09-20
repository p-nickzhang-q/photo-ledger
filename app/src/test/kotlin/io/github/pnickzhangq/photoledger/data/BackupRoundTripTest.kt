// S4 接缝测试（票 11）：备份 round-trip 无损 + CSV 导出。
// 备份 = 可出可进（库 → JSON 字符串 → 恢复进另一内存库 → 逐字段比对）；
// CSV = 只出不进（BOM / 表头 / RFC4180 转义）。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    private lateinit var db1: LedgerDatabase
    private lateinit var db2: LedgerDatabase
    private lateinit var repo1: LedgerRepository
    private lateinit var repo2: LedgerRepository
    private lateinit var photoRoot: File
    private lateinit var photoRoot2: File

    private val draft = Draft(
        merchant = "带图订单",
        amountPaid = 9.9,
        currency = "CNY",
        datePaid = "2026-09-01 10:00:00",
        dateSource = DateSource.PAYMENT_TIME,
        orderStatus = "已完成",
        category = "餐饮",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db1 = LedgerDatabase.inMemory(context)
        db2 = LedgerDatabase.inMemory(context)
        photoRoot = File(context.cacheDir, "backup-test-${System.nanoTime()}")
        photoRoot2 = File(context.cacheDir, "backup-test2-${System.nanoTime()}")
        repo1 = LedgerRepository(db1, PhotoStore(photoRoot))
        repo2 = LedgerRepository(db2, PhotoStore(photoRoot2))
    }

    @After
    fun tearDown() {
        db1.close()
        db2.close()
        photoRoot.deleteRecursively()
        photoRoot2.deleteRecursively()
    }

    private suspend fun seedSource() {
        repo1.addCategory("自定义A")
        repo1.addCategory("自定义B")
        repo1.addManual(
            merchant = "如意馄饨·干拌面光福店",
            amountPaid = 14.5,
            datePaid = "2026-08-18 12:30:00",
            category = "自定义A",
            dateSource = com.pnickzhangq.photoledger.engine.DateSource.PAYMENT_TIME,
            orderStatus = "已完成",
        )
        repo1.addManual(
            merchant = "含,逗号\"引号\n换行店",
            amountPaid = 0.0,
            datePaid = "",
            category = "",
            orderStatus = "",
        )
        // 一次编辑让 modifiedAt 与 createdAt 分离，验证时间戳保真
        repo1.addManual(
            merchant = "时间戳分离",
            amountPaid = 3.0,
            datePaid = "2026-07-01 08:00:00",
            category = "餐饮",
        )
        val e = repo1.entries.first().first { it.merchant == "时间戳分离" }
        repo1.edit(e) { copy(merchant = "时间戳分离改") }
    }

    @Test
    fun `备份 round-trip——Entry 逐字段无损`() = runTest {
        seedSource()
        val (entries, categories) = repo1.snapshot()
        val json = BackupCodec.encode(BackupCodec.fromEntities(entries, categories, "2026-09-20 12:00:00"))

        val data = BackupCodec.decode(json)
        repo2.restore(data)

        val restored = repo2.entries.first()
        assertEquals(entries.size, restored.size)
        entries.sortedBy { it.id }.zip(restored.sortedBy { it.id }).forEach { (src, dst) ->
            assertEquals(src.id, dst.id)
            assertEquals(src.merchant, dst.merchant)
            assertEquals(src.amountPaid, dst.amountPaid, 0.0)
            assertEquals(src.currency, dst.currency)
            assertEquals(src.datePaid, dst.datePaid)
            assertEquals(src.dateSource, dst.dateSource)
            assertEquals(src.orderStatus, dst.orderStatus)
            assertEquals(src.category, dst.category)
            assertEquals(src.photoPath, dst.photoPath)
            assertEquals(src.thumbPath, dst.thumbPath)
            assertEquals(src.createdAt, dst.createdAt)
            assertEquals(src.modifiedAt, dst.modifiedAt)
        }
    }

    @Test
    fun `备份 round-trip——类别含 sortOrder 无损`() = runTest {
        seedSource()
        val (entries, categories) = repo1.snapshot()
        val json = BackupCodec.encode(BackupCodec.fromEntities(entries, categories, "2026-09-20 12:00:00"))

        repo2.restore(BackupCodec.decode(json))
        val restored = repo2.categories.first()

        // 名称与顺序完全一致（id 允许重发）
        assertEquals(categories.map { it.name }, restored.map { it.name })
        assertEquals(categories.map { it.sortOrder }, restored.map { it.sortOrder })
        // 自定义类别确实进了备份（不只是种子八类）
        assertTrue(restored.any { it.name == "自定义A" })
        assertTrue(restored.any { it.name == "自定义B" })
    }

    @Test
    fun `备份恢复是替换语义——非空库恢复后只剩备份内容`() = runTest {
        seedSource()
        // 目标库先有自己的数据（种子八类 + 一条手工账）
        repo2.addManual(merchant = "旧数据", amountPaid = 1.0, datePaid = "2026-01-01 10:00:00", category = "餐饮")
        assertEquals(1, repo2.entries.first().size)

        val (entries, categories) = repo1.snapshot()
        repo2.restore(BackupCodec.decode(BackupCodec.encode(BackupCodec.fromEntities(entries, categories, "t"))))

        val restored = repo2.entries.first()
        assertEquals(entries.sortedBy { it.id }.map { it.merchant }, restored.sortedBy { it.id }.map { it.merchant })
        assertTrue(restored.none { it.merchant == "旧数据" })
        // 类别同样被替换：不再含「餐饮」之外由种子提供而源库没有的类别差异
        val names = repo2.categories.first().map { it.name }
        assertEquals(categories.map { it.name }, names)
    }

    @Test
    fun `decode 拒绝非备份文件`() {
        try {
            BackupCodec.decode("""{"hello":"world"}""")
            throw AssertionError("应当抛出")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("备份"))
        }
    }

    @Test
    fun `CSV——BOM 与表头`() = runTest {
        seedSource()
        val (entries, _) = repo1.snapshot()
        val csv = BackupCodec.toCsv(entries)
        assertTrue(csv.startsWith(BackupCodec.CSV_BOM))
        val firstLine = csv.removePrefix(BackupCodec.CSV_BOM).substringBefore("\r\n")
        assertEquals("付款时间,商家,实付款,币种,类别,日期口径,订单状态,创建时间,修改时间", firstLine)
        assertEquals(entries.size + 1, csv.split("\r\n").size)
    }

    @Test
    fun `CSV——逗号引号换行按 RFC4180 转义，中文原样`() = runTest {
        seedSource()
        val (entries, _) = repo1.snapshot()
        val csv = BackupCodec.toCsv(entries)
        assertTrue(csv.contains("\"含,逗号\"\"引号"))
        assertTrue(csv.contains("换行店\""))
        assertTrue(csv.contains("如意馄饨·干拌面光福店"))
        // 引号内的 \n 不产生裸换行：仍是表头 + 每条账目一行
        assertEquals(entries.size + 1, csv.split("\r\n").size)
    }

    @Test
    fun `CSV——不含照片内部路径与 id`() = runTest {
        seedSource()
        val (entries, _) = repo1.snapshot()
        val csv = BackupCodec.toCsv(entries)
        assertTrue(!csv.contains("photos/"))
        assertTrue(!csv.contains("thumb"))
    }

    @Test
    fun `备份 zip round-trip——照片与缩略图字节级无损`() = runTest {
        seedSource()
        repo1.confirm(draft, photoBytes = byteArrayOf(1, 2, 3, 4, 5))
        // 缩略图文件手工造（PhotoStore.save 无 Bitmap 时 thumbPath 为 null；备份层只管文件字节）
        val entry = repo1.entries.first().first { it.merchant == "带图订单" }
        File(photoRoot, "thumbs/${entry.photoPath}").writeBytes(byteArrayOf(9, 8, 7))
        db1.entryDao().update(entry.copy(thumbPath = entry.photoPath))

        val bos = ByteArrayOutputStream()
        val (entryCount, photoCount) = repo1.exportBackupZip(bos)
        assertEquals(4, entryCount)
        assertEquals(1, photoCount)

        val contents = BackupZip.read(ByteArrayInputStream(bos.toByteArray()))
        assertTrue(contents.photos.keys.single().endsWith(".png"))
        assertTrue(contents.thumbs.keys.single().endsWith(".png"))
        repo2.restoreBackupZip(contents.json, contents.photos, contents.thumbs)

        val restored = repo2.entries.first().first { it.merchant == "带图订单" }
        assertEquals(entry.photoPath, restored.photoPath)
        // entry 是 update 前的旧副本（thumbPath 当时为 null），比对 photoPath 同名文件
        assertEquals(entry.photoPath, restored.thumbPath)
        assertEquals(
            byteArrayOf(1, 2, 3, 4, 5).toList(),
            File(photoRoot2, "photos/${restored.photoPath}").readBytes().toList(),
        )
        assertEquals(
            byteArrayOf(9, 8, 7).toList(),
            File(photoRoot2, "thumbs/${restored.thumbPath}").readBytes().toList(),
        )
    }

    @Test
    fun `备份 zip——缺失照片文件跳过不报错`() = runTest {
        seedSource()
        repo1.confirm(draft, photoBytes = byteArrayOf(1))
        // 把照片文件删掉（模拟悬空引用）
        val entry = repo1.entries.first().first { it.merchant == "带图订单" }
        File(photoRoot, "photos/${entry.photoPath}").delete()

        val bos = ByteArrayOutputStream()
        val (_, photoCount) = repo1.exportBackupZip(bos)
        assertEquals(0, photoCount)
        // 备份仍可用：恢复后账目无损（照片引用悬空）
        val contents = BackupZip.read(ByteArrayInputStream(bos.toByteArray()))
        repo2.restoreBackupZip(contents.json, contents.photos, contents.thumbs)
        assertEquals(4, repo2.entries.first().size)
    }

    @Test
    fun `BackupZip read——非备份包显性失败`() {
        try {
            BackupZip.read(ByteArrayInputStream("not a zip".toByteArray()))
            throw AssertionError("应当抛出")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("备份"))
        }
    }
}
