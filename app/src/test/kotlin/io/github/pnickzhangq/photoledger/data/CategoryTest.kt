// 票 08：类别体系 S3 接缝测试（JVM + Room 内存库，同 LedgerRepositoryTest 模式）。
// 断言外部行为：首启种子、增删改、删类别其下 Entry 归「其他」、重命名级联、「其他」不可删。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pnickzhangq.photoledger.engine.DEFAULT_CATEGORIES
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import com.pnickzhangq.photoledger.engine.FALLBACK_CATEGORY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CategoryTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var photoRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = LedgerDatabase.inMemory(context)
        photoRoot = File(context.cacheDir, "category-test-${System.nanoTime()}")
        repo = LedgerRepository(db, PhotoStore(photoRoot))
    }

    @After
    fun tearDown() {
        db.close()
        photoRoot.deleteRecursively()
    }

    private suspend fun addEntry(category: String): Long = repo.confirm(
        Draft(
            merchant = "测试商家",
            amountPaid = 10.0,
            currency = "CNY",
            datePaid = "2026-09-01 10:00:00",
            dateSource = DateSource.PAYMENT_TIME,
            orderStatus = "",
            category = category,
        ),
    )

    /** suspend 版 assertThrows（JUnit 原生 lambda 非 suspend 上下文）。 */
    private suspend fun assertIllegal(action: suspend () -> Unit) {
        val caught = try {
            action()
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        if (caught == null) throw AssertionError("预期 IllegalArgumentException")
    }

    @Test
    fun `首启种子八类——按内置顺序`() = runTest {
        val names = repo.categoryNames()
        assertEquals(DEFAULT_CATEGORIES, names)
    }

    @Test
    fun `新增类别——尾部追加`() = runTest {
        repo.addCategory("宠物")
        val names = repo.categoryNames()
        // 票 37：「其他」恒末尾，新增类别插在它之前
        assertEquals(DEFAULT_CATEGORIES.dropLast(1) + "宠物" + FALLBACK_CATEGORY, names)
    }

    @Test
    fun `重名拒绝——含与内置类别冲突`() = runTest {
        assertIllegal { repo.addCategory("餐饮") }
        repo.addCategory("宠物")
        assertIllegal { repo.addCategory(" 宠物 ") } // trim 后同名也拒
    }

    @Test
    fun `空名拒绝`() = runTest {
        assertIllegal { repo.addCategory("  ") }
    }

    @Test
    fun `重命名级联——其下 Entry 引用同步更新`() = runTest {
        val transportId = db.categoryDao().list().first { it.name == "交通" }.id
        addEntry("交通")
        repo.renameCategory(transportId, "出行")
        assertEquals(DEFAULT_CATEGORIES.map { if (it == "交通") "出行" else it }, repo.categoryNames())
        assertEquals("出行", repo.entries.first().single().category)
    }

    @Test
    fun `删类别——其下 Entry 归其他而不丢账`() = runTest {
        val entryId = addEntry("交通")
        val transportId = db.categoryDao().list().first { it.name == "交通" }.id
        repo.deleteCategory(transportId)
        // 类别消失，账目还在且归「其他」
        assertFalse(repo.categoryNames().contains("交通"))
        val entry = db.entryDao().byId(entryId)!!
        assertEquals(FALLBACK_CATEGORY, entry.category)
        assertEquals(1, db.entryDao().count())
    }

    @Test
    fun `其他不可删也不可改名`() = runTest {
        val fallbackId = db.categoryDao().list().first { it.name == FALLBACK_CATEGORY }.id
        assertIllegal { repo.deleteCategory(fallbackId) }
        assertIllegal { repo.renameCategory(fallbackId, "杂项") }
        assertTrue(repo.categoryNames().contains(FALLBACK_CATEGORY))
    }

    // ---- 票 36：拖拽排序 ----

    @Test
    fun `类别排序——按传入 id 顺序重写并持久`() = runTest {
        val names = repo.categoryNames()                       // 票 37：「其他」已在末尾
        val others = names.filter { it != FALLBACK_CATEGORY }
        val ids = db.categoryDao().list().associate { it.name to it.id }
        // 把最后一个可排序类别拖到最前
        val moved = listOf(others.last()) + others.dropLast(1)
        repo.reorderCategories(moved.map { ids.getValue(it) })
        assertEquals(moved + FALLBACK_CATEGORY, repo.categoryNames())
        // 再拖回原顺序
        repo.reorderCategories(others.map { ids.getValue(it) })
        assertEquals(names, repo.categoryNames())
    }

    @Test
    fun `类别排序——未列出的 id 保持相对顺序追加尾部`() = runTest {
        val others = repo.categoryNames().filter { it != FALLBACK_CATEGORY }
        val ids = db.categoryDao().list().associate { it.name to it.id }
        // 只传最后一类，其余应原序跟在后面，「其他」仍垫底
        val moved = others.last()
        repo.reorderCategories(listOf(ids.getValue(moved)))
        assertEquals(listOf(moved) + others.dropLast(1) + FALLBACK_CATEGORY, repo.categoryNames())
    }

    // ---- 票 37：「其他」恒在末尾 ----

    @Test
    fun `其他恒末尾——重排传入的中间位置被忽略`() = runTest {
        val names = repo.categoryNames()
        val ids = db.categoryDao().list().associate { it.name to it.id }
        val fallbackId = ids.getValue(FALLBACK_CATEGORY)
        val others = names.filter { it != FALLBACK_CATEGORY }
        // 故意把「其他」插到第三位传进来
        val withFallbackMiddle = others.take(2) + FALLBACK_CATEGORY + others.drop(2)
        repo.reorderCategories(withFallbackMiddle.map { ids.getValue(it) })
        assertEquals(FALLBACK_CATEGORY, repo.categoryNames().last())
    }

    @Test
    fun `其他恒末尾——库里 sort_order 被改到最前，读取仍排末尾`() = runTest {
        val fallback = db.categoryDao().list().first { it.name == FALLBACK_CATEGORY }
        db.categoryDao().update(fallback.copy(sortOrder = -1))
        assertEquals(FALLBACK_CATEGORY, repo.categories.first().last().name)
        assertEquals(FALLBACK_CATEGORY, repo.categoryNames().last())
    }
}
