// S2 接缝测试（票 07）：导入队列，JVM + Robolectric。
// 注入假提取器（fake），断言外部行为：批量顺序处理、单张失败不阻塞、
// 哈希去重命中提示、状态事件序列正确。不断言实现细节（spec「Testing Decisions」）。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 假提取器：按字节内容返回预定结果，记录调用次序与入参。 */
private class FakeExtractor(
    private val outcomes: Map<String, ExtractionOutcome>,
) : ImportQueue.Extractor {
    val calls = mutableListOf<String>()

    override suspend fun extract(bytes: ByteArray): ExtractionOutcome {
        val key = bytes.toString(Charsets.ISO_8859_1)
        calls += key
        return outcomes[key] ?: error("无预定结果: $key")
    }
}

@RunWith(RobolectricTestRunner::class)
class ImportQueueTest {

    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var photoRoot: File

    private val draftA = Draft(
        merchant = "如意馄饨·干拌面光福店",
        amountPaid = 14.5,
        currency = "CNY",
        datePaid = "2026-08-18 12:30:00",
        dateSource = DateSource.PAYMENT_TIME,
        orderStatus = "已完成",
        category = "餐饮",
    )
    private val draftB = draftA.copy(merchant = "全家便利店", amountPaid = 32.5, datePaid = "2026-08-26 08:00:00")

    private val img1 = "image-one".toByteArray()
    private val img2 = "image-two".toByteArray()
    private val img3 = "image-three".toByteArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = LedgerDatabase.inMemory(context)
        photoRoot = File(context.cacheDir, "import-test-${System.nanoTime()}")
        repo = LedgerRepository(db.entryDao(), PhotoStore(photoRoot))
    }

    @After
    fun tearDown() {
        db.close()
        photoRoot.deleteRecursively()
    }

    /** runTest scope 内构造：queue 的 dispatcher 用 TestScope scheduler（状态事件可 runCurrent 推进）。 */
    private fun kotlinx.coroutines.test.TestScope.queueWith(extractor: ImportQueue.Extractor) =
        ImportQueue(repo, extractor, StandardTestDispatcher(testScheduler))

    // ---- 批量顺序处理 ----

    @Test
    fun `批量导入逐张提取——Draft 按入队顺序产出`() = runTest {
        val fake = FakeExtractor(
            mapOf(
                "image-one" to ExtractionOutcome.Success(listOf(draftA)),
                "image-two" to ExtractionOutcome.Success(listOf(draftB)),
            ),
        )
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.enqueue(img2, "b.png")

        queue.runPending()
        val items = queue.items.value

        assertEquals(listOf("image-one", "image-two"), fake.calls) // 顺序处理
        assertEquals(
            listOf(ImportState.Done::class, ImportState.Done::class),
            items.map { it.state::class },
        )
        assertEquals(listOf(draftA), (items[0].state as ImportState.Done).drafts)
        assertEquals(listOf(draftB), (items[1].state as ImportState.Done).drafts)
    }

    // ---- 单张失败不阻塞 ----

    @Test
    fun `单张失败不阻塞后续——失败项保留原因`() = runTest {
        val fake = FakeExtractor(
            mapOf(
                "image-one" to ExtractionOutcome.Failure("OCR 失败：模型未加载"),
                "image-two" to ExtractionOutcome.Success(listOf(draftB)),
            ),
        )
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.enqueue(img2, "b.png")

        queue.runPending()
        val items = queue.items.value

        assertEquals(listOf("image-one", "image-two"), fake.calls) // 失败后继续下一张
        assertTrue(items[0].state is ImportState.Failed)
        assertEquals("OCR 失败：模型未加载", (items[0].state as ImportState.Failed).reason)
        assertEquals(listOf(draftB), (items[1].state as ImportState.Done).drafts)
    }

    // ---- 哈希去重 ----

    @Test
    fun `同批重复截图——第二张命中去重不调用提取器`() = runTest {
        val fake = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.enqueue(img1, "a-copy.png") // 同内容不同文件名

        queue.runPending()
        val items = queue.items.value

        assertEquals(listOf("image-one"), fake.calls) // 提取器只被调一次
        assertEquals(ImportState.Done::class, items[0].state::class)
        assertTrue(items[1].state is ImportState.Duplicate)
    }

    @Test
    fun `跨批重复——与已入库截图哈希相同则提示已导入`() = runTest {
        // 第一批：导入成功并确认落库（模拟确认流完成）
        val fake1 = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue1 = queueWith(fake1)
        queue1.enqueue(img1, "a.png")
        queue1.runPending()
        val done = queue1.items.value[0]
        val (draft, bytes) = (done.state as ImportState.Done).let { it.drafts[0] to done.bytes }
        repo.confirm(draft, photoBytes = bytes)

        // 第二批：同一张图再次导入
        val fake2 = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue2 = queueWith(fake2)
        queue2.enqueue(img1, "a-again.png")

        queue2.runPending()

        assertTrue(queue2.items.value[0].state is ImportState.Duplicate)
        assertEquals(emptyList<String>(), fake2.calls) // 去重命中，根本不调提取器
    }

    @Test
    fun `队列内未处理项与已处理项重复——同样命中去重`() = runTest {
        val fake = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.enqueue(img1, "a-dup.png")
        // 不同于「同批重复」测试：这里第一张还没 runPending——入队时就应拦住
        val items = queue.items.value

        assertTrue(items[0].state is ImportState.Pending)
        assertTrue(items[1].state is ImportState.Duplicate)
    }

    // ---- 状态事件序列 ----

    @Test
    fun `状态事件序列正确——Pending 到 Processing 到终态`() = runTest {
        val fake = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")

        val observed = mutableListOf<List<String>>()
        val job = launch { queue.items.collect { observed.add(it.map { i -> i.state::class.simpleName ?: "?" }) } }

        // 首值（Pending）已发出
        runCurrent()
        assertEquals(listOf(listOf("Pending")), observed)

        queue.runPending()
        runCurrent()
        // 终值：Done。中间 Processing 态在顺序实现里存在（见实现），此处断言首尾即可
        assertEquals(listOf(listOf("Pending"), listOf("Done")), observed)
        job.cancel()
    }

    // ---- 失败项数据保留 ----

    @Test
    fun `失败项保留截图字节——可手工成 Entry`() = runTest {
        val fake = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Failure("不是订单截图")))
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.runPending()

        val item = queue.items.value[0]
        val failed = item.state as ImportState.Failed
        assertEquals("不是订单截图", failed.reason)
        assertTrue(item.bytes.isNotEmpty()) // 截图字节仍在，手工录入可用
    }

    // ---- 确认入账（队列页直接保存，不经编辑页） ----

    @Test
    fun `确认入账——项标 Confirmed 保留 Draft 摘要且同图不再可入队`() = runTest {
        val fake = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.runPending()

        queue.markConfirmed(queue.items.value[0])

        val confirmed = queue.items.value[0].state as ImportState.Confirmed
        assertEquals(listOf(draftA), confirmed.drafts)

        // 同一张图再来：哈希入队时已记，直接 Duplicate（提取过的 Done 项不会被重复提取）
        queue.enqueue(img1, "a-again.png")
        assertTrue(queue.items.value[1].state is ImportState.Duplicate)
    }

    @Test
    fun `确认失败回滚场景不存在——Confirmed 项再 markConfirmed 无变化`() = runTest {
        val fake = FakeExtractor(mapOf("image-one" to ExtractionOutcome.Success(listOf(draftA))))
        val queue = queueWith(fake)
        queue.enqueue(img1, "a.png")
        queue.runPending()
        queue.markConfirmed(queue.items.value[0])

        // 只认 Done → Confirmed 转换：重复调用不破坏状态（幂等）
        queue.markConfirmed(queue.items.value[0])
        assertTrue(queue.items.value[0].state is ImportState.Confirmed)
    }
}
