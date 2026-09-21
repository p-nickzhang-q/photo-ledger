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

    /**
     * 提取成功，待确认。一图多单时 drafts 多条；confirmed 记录已入账的下标——
     * 逐单确认（全部确认后转 Confirmed）。
     */
    data class Done(
        val drafts: List<Draft>,
        val confirmed: Set<Int> = emptySet(),
    ) : ImportState()

    /** 去重命中：这张已导入过（同批或已入库），不重复成账。 */
    data object Duplicate : ImportState()

    /** 全部单已确认入账（要改字段到账目详情）。保留 drafts 供列表展示。 */
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
    /** 截图文件修改时间（≈支付时间）。空日期草稿确认时的兜底，优先于「导入当天」。 */
    val fallbackDate: String? = null,
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
    suspend fun enqueue(bytes: ByteArray, displayName: String, fallbackDate: String? = null): Boolean = mutex.withLock {
        val hash = sha256(bytes)
        if (hash in importedHashes || _items.value.any { it.hash == hash && it.state !is ImportState.Failed }) {
            _items.value += ImportItem(bytes, displayName, ImportState.Duplicate, hash, fallbackDate)
            return true
        }
        _items.value += ImportItem(bytes, displayName, ImportState.Pending, hash, fallbackDate)
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
     * 确认单条入账（一图多单逐单确认）：drafts[index] 落库。
     * 无日期草稿按导入当天入账（策略在队列层，S2 可测）。
     * 返回入账的 Draft；状态非 Done / 下标越界 / 已确认过返回 null。
     */
    suspend fun confirmDraft(item: ImportItem, index: Int): Draft? = mutex.withLock {
        val idx = _items.value.indexOfFirst { it.hash == item.hash }
        if (idx < 0) null else confirmLocked(idx, index)
    }

    /** 该项全部未确认单一次入账，返回本次入账的 Draft 列表（无可确认单返回空）。 */
    suspend fun confirmAll(item: ImportItem): List<Draft> = mutex.withLock {
        val idx = _items.value.indexOfFirst { it.hash == item.hash }
        if (idx < 0) return emptyList()
        val state = _items.value[idx].state as? ImportState.Done ?: return emptyList()
        state.drafts.indices.mapNotNull { confirmLocked(idx, it) }
    }

    /**
     * 单条确认的落库 + 状态推进（调用方须持锁；repo.confirm 在锁内串行执行，
     * 防止两次快速点击读到过期 confirmed 集合互相覆盖）。
     */
    private suspend fun confirmLocked(idx: Int, index: Int): Draft? {
        val state = _items.value[idx].state as? ImportState.Done ?: return null
        if (index !in state.drafts.indices || index in state.confirmed) return null
        val draft = state.drafts[index]
        val effective = if (draft.datePaid.isBlank()) {
            // 空日期兜底：截图文件时间（≈支付时间）优先于「导入当天」——补导历史截图不记错天
            draft.copy(datePaid = _items.value[idx].fallbackDate ?: java.time.LocalDate.now().toString())
        } else {
            draft
        }
        repo.confirm(draft = effective, photoBytes = _items.value[idx].bytes)
        val confirmed = state.confirmed + index
        val newState = if (confirmed.size == state.drafts.size) {
            ImportState.Confirmed(state.drafts)
        } else {
            state.copy(confirmed = confirmed)
        }
        _items.value = _items.value.toMutableList().also { it[idx] = it[idx].copy(state = newState) }
        return effective
    }
}
