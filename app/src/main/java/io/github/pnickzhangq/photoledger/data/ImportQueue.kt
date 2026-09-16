// 票 07：导入队列（S2 接缝）。相册多选/系统分享汇入同一队列，逐张顺序提取，
// 单张失败不阻塞后续；内容哈希去重（同批内 + 跨批已入库）；失败项保留截图字节
// 供手工成 Entry。状态经 StateFlow 暴露给 UI。提取器为注入点（S2 测 fake，真机接 OnDevicePipeline）。
package io.github.pnickzhangq.photoledger.data

import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** 单张提取结果（注入点返回类型）：成功带 Draft 列表，失败带用户可读原因。 */
sealed class ExtractionOutcome {
    data class Success(val drafts: List<Draft>) : ExtractionOutcome()
    data class Failure(val reason: String) : ExtractionOutcome()
}

/** 队列项状态机：Pending → Processing → Done | Duplicate | Failed；Done → Confirmed（确认入账）。 */
sealed class ImportState {
    data object Pending : ImportState()
    data object Processing : ImportState()

    /** 提取成功，待确认（drafts 单 v1 契约取首个，列表保留多单扩展位）。 */
    data class Done(val drafts: List<Draft>) : ImportState()

    /** 去重命中：这张已导入过（同批或已入库），不重复成账。 */
    data object Duplicate : ImportState()

    /** 已确认入账（队列页直接保存，不经编辑页；要改字段到账目详情）。保留 drafts 供列表展示。 */
    data class Confirmed(val drafts: List<Draft>) : ImportState()

    /** 提取失败：原因上屏，截图字节保留（item.bytes）供手工成 Entry。 */
    data class Failed(val reason: String) : ImportState()
}

/** 队列中的一项：截图字节 + 原始显示名 + 当前状态。bytes 供确认入账/失败手工录入复用。 */
data class ImportItem(
    val bytes: ByteArray,
    val displayName: String,
    val state: ImportState,
    /** 内容哈希（SHA-256 hex），去重键。 */
    val hash: String,
)

class ImportQueue(
    private val repo: LedgerRepository,
    private val extractor: Extractor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /** 提取器接缝（S2）：字节进、结果出。真机实现桥接 OnDevicePipeline。 */
    fun interface Extractor {
        suspend fun extract(bytes: ByteArray): ExtractionOutcome
    }

    private val _items = MutableStateFlow<List<ImportItem>>(emptyList())
    val items: StateFlow<List<ImportItem>> = _items.asStateFlow()

    /**
     * 已知截图哈希：①构造时从照片目录反推（照片只在确认落库时写入，目录内文件=已导入；
     * 跨批/跨进程重启去重）；②本批入队即记（同批去重），提取失败时回退（可重试）。
     */
    private val importedHashes = HashSet<String>()

    private val mutex = Mutex()

    /** 内容哈希（SHA-256）。 */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    init {
        // 磁盘 IO 仅一次；确认流（票 06）只在 confirm 时写照片，无孤儿路径。
        repo.photoStore.photosDir.listFiles().orEmpty().forEach { f ->
            runCatching { importedHashes += sha256(f.readBytes()) }
        }
    }

    /**
     * 入队一张截图。入队时即做哈希去重（同批已入队/历史已入库）——重复项直接标 Duplicate，
     * 不进 Pending、不消耗提取。返回是否真正入队。
     */
    suspend fun enqueue(bytes: ByteArray, displayName: String): Boolean = mutex.withLock {
        val hash = sha256(bytes)
        if (hash in importedHashes || _items.value.any { it.hash == hash && it.state !is ImportState.Failed }) {
            _items.value += ImportItem(bytes, displayName, ImportState.Duplicate, hash)
            return true
        }
        _items.value += ImportItem(bytes, displayName, ImportState.Pending, hash)
        importedHashes += hash // 同批去重：入队即记；提取失败在 runPending 回退
        return true
    }

    /** 当前待处理项数（UI 提示用）。 */
    val pendingCount: Int get() = _items.value.count { it.state is ImportState.Pending }

    /**
     * 处理所有 Pending 项：逐张顺序（S2 断言顺序处理），单张失败标 Failed 后继续。
     * 处理期间 UI 可从 [items] 读实时状态（Pending→Processing→终态）。
     */
    suspend fun runPending() {
        withContext(dispatcher) {
            while (true) {
                val next = mutex.withLock {
                    _items.value.indexOfFirst { it.state is ImportState.Pending }
                }
                if (next < 0) break
                val item = mutex.withLock { _items.value[next] }
                // 标 Processing（状态事件序列：Pending → Processing → 终态，S2 可观测）
                mutex.withLock {
                    _items.value = _items.value.toMutableList().also {
                        it[next] = item.copy(state = ImportState.Processing)
                    }
                }
                val outcome = try {
                    extractor.extract(item.bytes)
                } catch (t: Throwable) {
                    ExtractionOutcome.Failure("提取异常：${t.message}")
                }
                val finalState = when (outcome) {
                    is ExtractionOutcome.Success -> ImportState.Done(outcome.drafts)
                    is ExtractionOutcome.Failure -> {
                        // 失败不算「已导入」：回退入队时预记的哈希，同一张之后可重选重试
                        mutex.withLock { importedHashes.remove(item.hash) }
                        ImportState.Failed(outcome.reason)
                    }
                }
                mutex.withLock {
                    _items.value = _items.value.toMutableList().also {
                        it[next] = item.copy(state = finalState)
                    }
                }
            }
        }
    }

    /**
     * 确认入账完成：项标 [ImportState.Confirmed]（保留 Draft 摘要展示，按钮消失）。
     * 哈希在入队时已记 importedHashes，同图不会再次入队；照片落盘由 repo.confirm 完成。
     */
    suspend fun markConfirmed(item: ImportItem) {
        mutex.withLock {
            _items.value = _items.value.toMutableList().also { list ->
                val idx = list.indexOfFirst { it.hash == item.hash && it.state is ImportState.Done }
                if (idx >= 0) {
                    val done = list[idx].state as ImportState.Done
                    list[idx] = list[idx].copy(state = ImportState.Confirmed(done.drafts))
                }
            }
        }
    }
}
