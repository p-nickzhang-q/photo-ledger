// 票 04 冒烟 + 票 05 端到端 + 票 06 账目库与确认流。
// 界面导航：流水列表（主体）→ Draft 确认 / Entry 详情 / 手工新增；冒烟工具页保留为开发入口。
// 并发层协程（票04 重构）：CPU 密集（OCR/LLM）走 Dispatchers.Default，销毁由 lifecycleScope 取消。
package io.github.pnickzhangq.photoledger

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.pnickzhangq.photoledger.data.BackupCodec
import io.github.pnickzhangq.photoledger.data.BackupZip
import io.github.pnickzhangq.photoledger.data.Entry
import io.github.pnickzhangq.photoledger.data.ImportQueue
import io.github.pnickzhangq.photoledger.data.LedgerDatabase
import io.github.pnickzhangq.photoledger.data.LedgerRepository
import io.github.pnickzhangq.photoledger.data.PhotoStore
import io.github.pnickzhangq.photoledger.model.ModelManager
import io.github.pnickzhangq.photoledger.ocr.ExtractResult
import io.github.pnickzhangq.photoledger.ocr.JniLlmTransport
import io.github.pnickzhangq.photoledger.ocr.LitertLlmTransport
import io.github.pnickzhangq.photoledger.ocr.LlamaNative
import io.github.pnickzhangq.photoledger.ocr.OcrEngine
import io.github.pnickzhangq.photoledger.ocr.OcrLine
import io.github.pnickzhangq.photoledger.ocr.OnDevicePipeline
import io.github.pnickzhangq.photoledger.ui.BackupScreen
import io.github.pnickzhangq.photoledger.ui.CategoryManageScreen
import io.github.pnickzhangq.photoledger.ui.DraftConfirmScreen
import io.github.pnickzhangq.photoledger.ui.DraftForm
import io.github.pnickzhangq.photoledger.ui.EntryEditScreen
import io.github.pnickzhangq.photoledger.ui.EntryForm
import io.github.pnickzhangq.photoledger.ui.ImportQueueScreen
import io.github.pnickzhangq.photoledger.ui.LedgerListScreen
import io.github.pnickzhangq.photoledger.ui.ModelManageScreen
import io.github.pnickzhangq.photoledger.ui.SummaryScreen
import com.pnickzhangq.photoledger.engine.DEFAULT_CATEGORIES
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 页面导航（无路由库，sealed class 足够本规模）。 */
private sealed class Page {
    data object Ledger : Page()
    data class Confirm(val draft: Draft, val photoPath: String?) : Page()
    data class Detail(val entryId: Long) : Page()
    data object SmokeTools : Page()
    data object ModelManage : Page()    // 票 10：模型管理（下载/导入/RAM 预检；首启引导页）
    data object ImportQueue : Page()   // 票 07
    data object CategoryManage : Page() // 票 08
    data object Summary : Page()        // 票 09
    data object Backup : Page()         // 票 11：备份与导出
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "photoledger-smoke"
        // 票 05 验收：内存门槛。0.6B Q4_K_M 峰值 ~0.5GB + OCR ~20MB + 运行时 ~0.3GB。
        private const val MIN_AVAILABLE_MB = 2048L
        // 票 08：类别体系入库（首启种子 = engine.DEFAULT_CATEGORIES），此常量已移除；
        // UI 首帧回退用 DEFAULT_CATEGORIES（种子落库前的空窗）。
    }

    /** 开发期模型目录（票04）：adb push 到 App 专属外部目录。 */
    private val pushDir: File
        get() = getExternalFilesDir(null) ?: filesDir

    // ---- 账目库 ----
    private lateinit var repo: LedgerRepository

    // ---- 页栈导航（BackHandler 拦截系统返回逐页回退；根页放行 = 系统默认退出）----
    // 首启无模型时根页是模型引导页（onCreate 决定，见 modelPrefs）
    private val pageStack = androidx.compose.runtime.mutableStateListOf<Page>()
    private val currentPage: Page get() = pageStack.last()
    private fun navigate(p: Page) {
        if (currentPage != p) pageStack.add(p) // 同页不重复压栈（队列页再分享/再导入场景）
    }
    private fun goBack() {
        if (pageStack.size > 1) pageStack.removeAt(pageStack.size - 1)
    }
    private fun backToRoot() {
        pageStack.clear()
        pageStack.add(Page.Ledger)
    }

    // ---- 导入队列（票 07）——lifecycleScope 下懒建：需要 repo 就绪。
    // 必须是快照状态：顶栏直达进入队列页时它还是 null，首次导入才创建——
    // 若为普通变量，Compose 感知不到创建，页面会卡在空态不刷新（真机 bug）。
    private var importQueue: ImportQueue? by mutableStateOf(null)
    private var importExtractor: ImportQueue.Extractor? = null

    // ---- 冒烟 UI 状态 ----
    private val status = mutableStateOf("工具页：选图/OCR/推理（票04/05 冒烟入口）")
    private val ocrResult = mutableStateOf("")
    private val llmResult = mutableStateOf("")
    private val e2eResult = mutableStateOf("")
    private val imageReady = mutableStateOf(false)
    private val llmReadyState = mutableStateOf(false)

    // ---- 已载入的图（冒烟/提取共用）----
    private var lastPixels: IntArray? = null
    private var lastW = 0
    private var lastH = 0
    private var lastPhotoBytes: ByteArray? = null   // 确认入账时的原图（SAF 读入）
    private var lastPhotoPath: String? = null       // 冒烟 intent 场景的原图路径

    // ---- 模型路径 ----
    private var detPath: String? = null
    private var recPath: String? = null
    private var clsPath: String? = null
    private var ggufPath: String? = null
    private var litertBackendOverride: String? = null  // 票 14：smoke intent 传 cpu/gpu 强制后端（A/B 对照）

    // 票 10：快照状态——下载/导入完成后 rescan 让模型页与各依赖处即时感知
    private var litertlmPath: String? by mutableStateOf(null)

    // ---- 模型管理（票 10）----
    private val modelPrefs by lazy { getSharedPreferences("model", MODE_PRIVATE) }
    private var downloadState by mutableStateOf<ModelManager.DownloadState>(ModelManager.DownloadState.Idle)
    private var selectedSource by mutableStateOf(0)
    private var importMsg by mutableStateOf<String?>(null)
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var totalRamBytes: Long = 0L

    // ---- 备份与导出（票 11）----
    private var backupMsg by mutableStateOf<String?>(null)
    private var showRestoreConfirm by mutableStateOf(false)
    private var pendingRestoreUri: Uri? = null

    // ---- 引擎与句柄 ----
    private var ocrEngine: OcrEngine? = null
    private var modelPtr = 0L
    private var ctxPtr = 0L
    private var llmReady = false
    private var modelFd: android.os.ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = LedgerRepository(
            LedgerDatabase.get(this),
            PhotoStore(File(filesDir, "ledger")),
        )
        scanModelDir()
        totalRamBytes = ModelManager.totalRamBytes(this)

        // 票 10：首启无模型 → 模型页作为根页（引导下载/导入）；用户跳过或模型就绪后不再强推
        val onboarding = litertlmPath == null && !modelPrefs.getBoolean("onboard_done", false)
        pageStack.clear()   // 防御：任何路径导致的重复 onCreate 不得叠栈
        pageStack.add(if (onboarding) Page.ModelManage else Page.Ledger)

        handleSmokeIntent(intent)   // 票 14：抽出复用（onNewIntent 同一入口，冷启动 race 时可重发）

        // 票 07：系统分享（SEND 单张 / SEND_MULTIPLE 多张）→ 导入队列
        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                AppScaffold()
            }
        }
    }

    /** 票 14：onNewIntent 也走冒烟入口——App 存活时重发 smoke intent 不再被忽略。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSmokeIntent(intent)
        handleShareIntent(intent)   // 票 07：App 存活时分享也进队列
    }

    /** 票 07：系统分享入口处理。SEND 单张 / SEND_MULTIPLE 多张，均入导入队列。 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        Log.i(TAG, "SHARE_INTENT action=${intent.action} type=${intent.type}")
        when (intent.action) {
            Intent.ACTION_SEND ->
                if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    Log.i(TAG, "SHARE_SEND uri=$uri")
                    if (uri != null) enqueueImports(listOf(uri to "分享截图"))
                }
            Intent.ACTION_SEND_MULTIPLE ->
                if (intent.type?.startsWith("image/") == true) {
                    @Suppress("DEPRECATION")
                    val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    Log.i(TAG, "SHARE_SEND_MULTIPLE count=${uris.size}")
                    enqueueImports(uris.mapIndexed { i, u -> u to "分享截图${i + 1}" })
                }
        }
    }

    /** 冒烟 intent 处理（票 04/05 建立，票 14 抽出）：结果落 logcat（SMOKE_*），不依赖读屏。 */
    private fun handleSmokeIntent(intent: Intent?) {
        val smokeImage = intent?.getStringExtra("smoke_image") ?: return
        val smokeLlm = intent.getBooleanExtra("smoke_llm", false)
        val smokeE2e = intent.getBooleanExtra("smoke_e2e", false)
        val smokeE2eLitert = intent.getBooleanExtra("smoke_e2e_litert", false)
        litertBackendOverride = intent.getStringExtra("litert_backend")
        Log.i(TAG, "SMOKE_INTENT image=$smokeImage e2e=$smokeE2e litert=$smokeE2eLitert backend=$litertBackendOverride")

        // 票 07：smoke_import=true 时截图入导入队列（真机验证队列全链路，绕 am 无法 grant 的限制）
        if (intent.getBooleanExtra("smoke_import", false)) {
            val path = resolveUnderPushDir(smokeImage)
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { runCatching { File(path).readBytes() }.getOrNull() }
                Log.i(TAG, "SMOKE_IMPORT bytes=${bytes?.size ?: -1}")
                if (bytes != null) {
                    val queue = ensureImportQueue()
                    navigate(Page.ImportQueue)
                    queue.enqueue(bytes, "队列测试.png")
                    queue.runPending()
                }
            }
            return
        }

        lifecycleScope.launch {
            loadBitmap(resolveUnderPushDir(smokeImage))?.let { px ->
                lastPhotoPath = resolveUnderPushDir(smokeImage)
                when {
                    smokeE2eLitert -> runE2eLitert(px)   // 票 13/14：LiteRT 后端
                    smokeE2e -> {
                        val ok = loadLlm()
                        if (ok) runE2e(px)
                    }
                    else -> {
                        val lines = runOcr(px)
                        if (smokeLlm && lines != null) {
                            val ok = loadLlm()
                            if (ok) runLlm()
                        }
                    }
                }
            }
        }
    }

    // ---- 顶层脚手架：列表页带 FAB（手工新增）+ 工具入口 ----

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun AppScaffold() {
        val current = currentPage
        val entries by repo.entries.collectAsState(initial = emptyList())
        // 票 08：类别体系入库；名称列表注入全部录入/提取场景（首帧回退内置八类）
        val categoryEntities by repo.categories.collectAsState(initial = emptyList())
        val categoryNames = categoryEntities.map { it.name }.ifEmpty { DEFAULT_CATEGORIES }
        // 类别页新增对话框状态（FAB 触发，hoist 到此以便 FAB 与屏幕共用）
        var showAddCategoryDialog by remember { mutableStateOf(false) }
        // 票 09：汇总数据（当月 = 今天所在月份；remember 固定 Flow 实例避免重组重挂）
        val monthTotals by repo.monthTotals.collectAsState(initial = emptyList())
        val currentMonth = remember { java.time.LocalDate.now().toString().take(7) }
        val currentCategoryTotals by remember(currentMonth) { repo.categoryTotals(currentMonth) }
            .collectAsState(initial = emptyList())
        // 队列订阅放顶层（不能在 when 分支内条件调用 collectAsState——队列懒创建的
        // null→非空转换会让分支错过订阅建立，页面卡在空态；快照状态保证创建即重组）
        val queueItems = importQueue?.items
            ?.let { flow -> flow.collectAsState(initial = emptyList()).value }
            ?: emptyList()

        // 系统返回手势/按键：非根页逐页回退，根页不拦截（系统默认退出）
        BackHandler(enabled = pageStack.size > 1) { goBack() }

        // 顶栏三段式统一：非根页 [← | 页面名 | —]，根页 [照片记账 | 队列·汇总·类别·工具]
        val pageTitle = when (current) {
            is Page.Ledger -> "照片记账"
            is Page.ImportQueue -> "导入队列"
            is Page.Confirm -> "确认入账"
            is Page.Detail -> "账目详情"
            is Page.CategoryManage -> "类别管理"
            is Page.Summary -> "汇总"
            is Page.ModelManage -> "模型管理"
            is Page.Backup -> "备份与导出"
            is Page.SmokeTools -> "开发工具"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(pageTitle) },
                    navigationIcon = {
                        if (pageStack.size > 1) {
                            IconButton(onClick = ::goBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    actions = {
                        if (current is Page.Ledger) {
                            // 识别队列直达入口（空队列给可行动的空态，不再只能先导图）
                            IconButton(onClick = { navigate(Page.ImportQueue) }) {
                                Icon(Icons.Filled.Queue, contentDescription = "导入队列")
                            }
                            IconButton(onClick = { navigate(Page.Summary) }) {
                                Icon(Icons.Filled.PieChart, contentDescription = "汇总")
                            }
                            IconButton(onClick = { navigate(Page.CategoryManage) }) {
                                Icon(Icons.Filled.Category, contentDescription = "类别管理")
                            }
                            IconButton(onClick = { navigate(Page.Backup) }) {
                                Icon(Icons.Filled.Backup, contentDescription = "备份与导出")
                            }
                            IconButton(onClick = { navigate(Page.ModelManage) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "模型管理")
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                when (current) {
                    // 主操作：相册选取照片进导入队列（手工记账已删，导入即全部入口）
                    is Page.Ledger -> FloatingActionButton(onClick = ::launchGalleryPick) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "导入截图")
                    }
                    is Page.CategoryManage -> FloatingActionButton(onClick = { showAddCategoryDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新增类别")
                    }
                    else -> {}
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (val p = current) {
                    is Page.Ledger -> LedgerListScreen(
                        entries = entries,
                        thumbDir = File(File(filesDir, "ledger"), "thumbs"),
                        photoDir = File(File(filesDir, "ledger"), "photos"),
                        emptyHint = "还没有账目\n\n点右下角 ➕ 从相册导入订单截图",
                        onEntryClick = { navigate(Page.Detail(it.id)) },
                        onImportFromGallery = ::launchGalleryPick,
                    )
                    is Page.ImportQueue -> ImportQueueScreen(
                        items = queueItems,
                        onImportFromGallery = ::launchGalleryPick,
                        onConfirmDraft = ::confirmFromQueue,
                        onConfirmAll = ::confirmAllFromQueue,
                    )
                    is Page.Confirm -> DraftConfirmScreen(
                        draft = p.draft,
                        photoPath = p.photoPath,
                        categories = categoryNames,
                        onConfirm = { form -> lifecycleScope.launch { confirmDraft(p, form) } },
                        onDiscard = { repo.discard(); goBack() },
                    )
                    is Page.CategoryManage -> CategoryManageScreen(
                        categories = categoryEntities,
                        showAddDialog = showAddCategoryDialog,
                        onAddDialogDismiss = { showAddCategoryDialog = false },
                        onAdd = { name, onResult ->
                            lifecycleScope.launch {
                                onResult(try { repo.addCategory(name); null } catch (e: Exception) { e.message ?: "添加失败" })
                            }
                        },
                        onRename = { id, newName, onResult ->
                            lifecycleScope.launch {
                                onResult(try { repo.renameCategory(id, newName); null } catch (e: Exception) { e.message ?: "重命名失败" })
                            }
                        },
                        onDelete = { id, _ ->
                            lifecycleScope.launch { repo.deleteCategory(id) }
                        },
                    )
                    is Page.Summary -> SummaryScreen(
                        monthTotals = monthTotals,
                        categoryTotals = currentCategoryTotals,
                        currentMonth = currentMonth,
                    )
                    is Page.ModelManage -> ModelManageScreen(
                        modelFile = litertlmPath?.let(::File)?.takeIf(File::exists),
                        ocrDetReady = detPath != null,
                        ocrRecReady = recPath != null,
                        ocrClsReady = clsPath != null,
                        totalRamBytes = totalRamBytes,
                        downloadState = downloadState,
                        selectedSource = selectedSource,
                        onSourceChange = { selectedSource = it },
                        onStartDownload = ::startModelDownload,
                        onCancelDownload = ::cancelModelDownload,
                        onImportModel = { pickModelFile.launch(arrayOf("*/*")) },
                        onImportOcr = { pickOcrFiles.launch(arrayOf("*/*")) },
                        ocrDownloadState = ocrDownloadState,
                        onOcrDownloadStart = ::startOcrDownload,
                        onOcrDownloadCancel = ::cancelOcrDownload,
                        importMsg = importMsg,
                        onExitOnboarding = if (pageStack.size == 1) {{
                            modelPrefs.edit().putBoolean("onboard_done", true).apply()
                            backToRoot()
                        }} else null,
                        onOpenDevTools = { navigate(Page.SmokeTools) },
                    )
                    is Page.Backup -> BackupScreen(
                        entryCount = entries.size,
                        categoryCount = categoryEntities.size,
                        message = backupMsg,
                        showRestoreConfirm = showRestoreConfirm,
                        onDismissRestoreConfirm = { showRestoreConfirm = false },
                        onConfirmRestore = {
                            showRestoreConfirm = false
                            pendingRestoreUri?.let { restoreFromBackup(it) }
                        },
                        onExportBackup = {
                            exportBackupDoc.launch("photo-ledger-backup-${stamp()}.zip")
                        },
                        onImportBackup = { importBackupDoc.launch(arrayOf("application/json", "text/*", "*/*")) },
                        onExportCsv = {
                            exportCsvDoc.launch("photo-ledger-${stamp()}.csv")
                        },
                    )
                    is Page.Detail -> {
                        val entry = entries.firstOrNull { it.id == p.entryId }
                        if (entry == null) {
                            Text("账目不存在（已删除？）", Modifier.padding(16.dp))
                            OutlinedButton(onClick = ::goBack, Modifier.padding(16.dp)) { Text("返回") }
                        } else {
                            EntryEditScreen(
                                entry = entry,
                                photoDir = File(File(filesDir, "ledger"), "photos"),
                                categories = categoryNames,
                                onSave = { form -> lifecycleScope.launch { saveEdit(entry, form) } },
                                onDelete = { alsoPhoto -> lifecycleScope.launch { repo.delete(entry, alsoPhoto); goBack() } },
                            )
                        }
                    }
                    is Page.SmokeTools -> SmokeScreen(
                        status = status.value,
                        ocrResult = ocrResult.value,
                        llmResult = llmResult.value,
                        e2eResult = e2eResult.value,
                        imageReady = imageReady.value,
                        llmReady = llmReadyState.value,
                        onPickImage = { pickImage.launch("image/*") },
                        onOcr = { lifecycleScope.launch { runOcr(lastPixels) } },
                        onPickModel = { pickModel.launch("*/*") },
                        onLlm = { lifecycleScope.launch { runLlm() } },
                        onE2e = { lifecycleScope.launch { runE2e(lastPixels) } },
                        onE2eLitert = { lifecycleScope.launch { runE2eLitert(lastPixels) } },
                    )
                }
            }
        }
    }

    // ---- 确认流（票 06）----

    private suspend fun confirmDraft(p: Page.Confirm, form: DraftForm) {
        repo.confirm(
            draft = p.draft,
            editedMerchant = form.merchant,
            editedAmount = form.amountPaid.toDoubleOrNull() ?: 0.0,
            editedDate = form.datePaid,
            editedCategory = form.category,
            editedOrderStatus = "",   // 订单状态退出 UI（2026-09）：存量值只在编辑路径保留
            photoBytes = lastPhotoBytes,
        )
        lastPhotoBytes = null
        backToRoot()   // 确认流程终点：回流水列表
    }

    private suspend fun saveEdit(entry: Entry, form: EntryForm) {
        repo.edit(entry) {
            // orderStatus 不在表单里：原样保留存量值（字段已退出 UI）
            copy(
                merchant = form.merchant,
                amountPaid = form.amountPaid.toDoubleOrNull() ?: 0.0,
                datePaid = form.datePaid,
                category = form.category,
            )
        }
        goBack()
    }

    // ---- 导入队列（票 07）：相册多选 + 系统分享 → 同一队列 → 确认流 ----

    /** 队列常驻 transport（票 07 提速）：批内每张不再新建/销毁引擎（原每张白付 ~4s 加载）。 */
    private var queueTransport: LitertLlmTransport? = null

    private fun obtainQueueTransport(): LitertLlmTransport {
        queueTransport?.let { return it }
        val modelPath = litertlmPath?.let(::File)?.takeIf(File::exists)?.absolutePath
            ?: error("缺识别模型：请到「模型管理」页下载或导入")
        val t = try {
            LitertLlmTransport(
                modelPath = modelPath,
                backend = com.google.ai.edge.litertlm.Backend.GPU(),
            ).also { it.ensureLoaded() }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU 不可用，队列提取用 CPU：$t")
            LitertLlmTransport(
                modelPath = modelPath,
                backend = com.google.ai.edge.litertlm.Backend.CPU(),
            ).also { it.ensureLoaded() }
        }
        queueTransport = t
        return t
    }

    /** 真实提取器：截图字节 → 解码像素 → OnDevicePipeline（transport 常驻复用）。 */
    private fun makeImportExtractor(): ImportQueue.Extractor {
        return ImportQueue.Extractor { bytes ->
            val engine = ocrEngine ?: OcrEngine(
                detModel = File(detPath ?: error("缺 det.onnx")),
                recModel = File(recPath ?: error("缺 rec.onnx")),
                clsModel = clsPath?.let(::File),
                threads = 4,
            ).also { ocrEngine = it }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@Extractor io.github.pnickzhangq.photoledger.data.ExtractionOutcome.Failure("图片解码失败")
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val w = bmp.width; val h = bmp.height
            bmp.recycle()

            // 票 08：逐张实时取当前类别列表（管理页改完，下一张提取即生效）
            val pipeline = OnDevicePipeline(engine, obtainQueueTransport(), repo.categoryNames())
            when (val r = pipeline.extract(pixels, w, h, fallbackYear = java.time.Year.now().value)) {
                is io.github.pnickzhangq.photoledger.ocr.ExtractResult.Success ->
                    io.github.pnickzhangq.photoledger.data.ExtractionOutcome.Success(r.drafts)
                is io.github.pnickzhangq.photoledger.ocr.ExtractResult.Failure ->
                    io.github.pnickzhangq.photoledger.data.ExtractionOutcome.Failure(r.reason)
            }
        }
    }

    /** 队列懒建（repo 就绪后）；复用同一实例保跨批哈希缓存。 */
    private fun ensureImportQueue(): ImportQueue =
        importQueue ?: ImportQueue(repo, makeImportExtractor()).also { importQueue = it }

    /** 把若干 SAF 图片字节入队并跳到队列页，随后逐张提取。 */
    private fun enqueueImports(list: List<Pair<Uri, String>>) {
        if (list.isEmpty()) return
        val queue = ensureImportQueue()
        navigate(Page.ImportQueue)
        lifecycleScope.launch {
            list.forEach { (uri, name) ->
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (t: Throwable) {
                        Log.e(TAG, "IMPORT_READ_FAIL $name uri=$uri", t)
                        null
                    }
                }
                Log.i(TAG, "IMPORT_ENQUEUE $name bytes=${bytes?.size ?: -1}")
                if (bytes != null) queue.enqueue(bytes, name)
            }
            queue.runPending()
        }
    }

    /** 队列 Done 项逐单入账（一图多单）。无日期草稿由队列层自动填导入当天。 */
    private fun confirmFromQueue(item: io.github.pnickzhangq.photoledger.data.ImportItem, index: Int) {
        lifecycleScope.launch { ensureImportQueue().confirmDraft(item, index) }
    }

    /** 队列 Done 项全部入账。 */
    private fun confirmAllFromQueue(item: io.github.pnickzhangq.photoledger.data.ImportItem) {
        lifecycleScope.launch { ensureImportQueue().confirmAll(item) }
    }

    // ---- 冒烟链路（票04/05，协程）----

    /** 读图并置 UI 状态；失败返回 null。SAF 源同时保留原始字节供入账。 */
    private suspend fun loadBitmap(path: String): IntArray? = withContext(Dispatchers.IO) {
        try {
            val bytes = File(path).readBytes()
            lastPhotoBytes = bytes
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: run {
                    status.value = "解码失败：$path"
                    return@withContext null
                }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            lastPixels = px
            lastW = bmp.width
            lastH = bmp.height
            imageReady.value = true
            status.value = "图就绪 ${bmp.width}x${bmp.height}，点 2.OCR"
            bmp.recycle()
            px
        } catch (t: Throwable) {
            Log.e(TAG, "decode image failed", t)
            status.value = "读图失败：${t.message}"
            null
        }
    }

    /** 跑 OCR；成功返回行列表并写 UI/logcat，失败返回 null。 */
    private suspend fun runOcr(pixels: IntArray?): List<OcrLine>? {
        val det = detPath ?: recMissing()
        val rec = recPath ?: recMissing()
        if (pixels == null || det == null || rec == null) return null
        status.value = "OCR 运行中…"
        return withContext(Dispatchers.Default) {
            try {
                val engine = ocrEngine ?: OcrEngine(
                    detModel = File(det),
                    recModel = File(rec),
                    clsModel = clsPath?.let(::File),
                    threads = 4,
                ).also { ocrEngine = it }
                val t0 = System.currentTimeMillis()
                val lines = engine.run(pixels, lastW, lastH)
                val ms = System.currentTimeMillis() - t0
                ocrResult.value = if (lines.isEmpty()) "(no lines)" else lines.joinToString("\n") {
                    "(${it.box[0][0].toInt()},${it.box[0][1].toInt()}) [${"%.2f".format(it.score)}] ${it.text}"
                }
                status.value = "OCR 完成：${lines.size} 行，${ms}ms"
                Log.i(TAG, "SMOKE_OCR_OK lines=${lines.size} ms=$ms")
                lines
            } catch (t: Throwable) {
                Log.e(TAG, "ocr failed", t)
                status.value = "OCR 失败：${t.message}"
                null
            }
        }
    }

    private fun recMissing(): String? {
        status.value = "缺模型（adb push 到 ${pushDir.absolutePath}）"
        return null
    }

    /** 加载 GGUF（扫描路径或 SAF fd）；成功 true 并写 logcat。 */
    private suspend fun loadLlm(path: String? = null): Boolean {
        val src = path ?: ggufPath
        if (src == null) {
            status.value = "无 GGUF（push 到 ${pushDir.absolutePath} 或 SAF 选择）"
            return false
        }
        status.value = "加载模型中…"
        return withContext(Dispatchers.Default) {
            try {
                val t0 = System.currentTimeMillis()
                LlamaNative.backendInit()
                modelPtr = LlamaNative.loadModel(src)
                ctxPtr = LlamaNative.newContext(modelPtr, nCtx = 2048, nThreads = 6)
                llmReady = true
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "SMOKE_LLM_LOAD_OK ms=$ms")
                llmReadyState.value = true
                status.value = "模型加载完成 ${ms}ms，点 4.推理"
                true
            } catch (t: Throwable) {
                Log.e(TAG, "load model failed", t)
                status.value = "模型加载失败：${t.message}"
                false
            }
        }
    }

    private suspend fun runLlm() {
        if (!llmReady || ctxPtr == 0L) {
            status.value = "先 3.选GGUF 加载"
            return
        }
        status.value = "推理中（约 1 分钟）…"
        withContext(Dispatchers.Default) {
            try {
                val t0 = System.currentTimeMillis()
                val out = LlamaNative.complete(ctxPtr, prompt = "1+1=", grammar = "", nLen = 64)
                val ms = System.currentTimeMillis() - t0
                llmResult.value = out
                status.value = "推理完成 ${ms}ms"
                Log.i(TAG, "SMOKE_LLM_OK ms=$ms out=${out.take(80)}")
            } catch (t: Throwable) {
                Log.e(TAG, "llm failed", t)
                status.value = "推理失败：${t.message}"
            }
        }
    }

    private fun checkMemoryGate(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024L * 1024L)
        Log.i(TAG, "SMOKE_E2E_MEM availableMb=$availMb thresholdMb=$MIN_AVAILABLE_MB")
        if (availMb < MIN_AVAILABLE_MB) {
            status.value = "可用内存 ${availMb}MB 低于 ${MIN_AVAILABLE_MB}MB 门槛，无法安全运行端侧提取"
            Log.e(TAG, "SMOKE_E2E_REJECTED low memory ${availMb}MB")
            return false
        }
        return true
    }

    /**
     * 端到端提取（票 05）：内存 gate → 共享引擎管线 → Draft。
     * 成功后进确认界面（票 06 确认流），不再直接展示结果。
     */
    /**
     * 票 14：LiteRT-LM GPU 后端端到端（INT4 GPU 优化档）。
     * 同一共享引擎管线，transport 默认 GPU/OpenCL；GPU 初始化失败自动降级 CPU。
     * 基准：vivo 同厂 GPU 580-1056 prefill / 21-22 decode tok/s，预期端到端 ~3-5s。
     */
    private suspend fun runE2eLitert(pixels: IntArray?): Unit = withContext(Dispatchers.Default) {
        if (pixels == null) {
            status.value = "先 1.选截图"
            return@withContext
        }
        val modelFile = litertlmPath?.let(::File)
        if (modelFile == null || !modelFile.exists()) {
            status.value = "缺 .litertlm 模型（adb push 到 ${pushDir.absolutePath}）"
            return@withContext
        }
        val engine = ocrEngine ?: OcrEngine(
            detModel = File(detPath ?: return@withContext.also { status.value = "缺 det.onnx" }),
            recModel = File(recPath ?: return@withContext.also { status.value = "缺 rec.onnx" }),
            clsModel = clsPath?.let(::File),
            threads = 4,
        ).also { ocrEngine = it }

        // 票 14：默认 GPU（manifest uses-native-library 解锁 OpenCL）；init 失败降级 CPU。
        // smoke intent 可传 litert_backend=cpu 强制 CPU（A/B 对照用），值存 litertBackendOverride。
        val wantGpu = !litertBackendOverride.equals("cpu", ignoreCase = true)
        status.value = "LiteRT 端到端（${if (wantGpu) "GPU" else "CPU"}…）"
        val transport = if (!wantGpu) {
            LitertLlmTransport(modelPath = modelFile.absolutePath, backend = com.google.ai.edge.litertlm.Backend.CPU())
                .also { it.ensureLoaded() }
        } else {
            try {
                LitertLlmTransport(modelPath = modelFile.absolutePath, backend = com.google.ai.edge.litertlm.Backend.GPU())
                    .also { it.ensureLoaded() }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU 后端初始化失败，降级 CPU：$t")
                status.value = "GPU 不可用（${t.message}），降级 CPU"
                LitertLlmTransport(modelPath = modelFile.absolutePath, backend = com.google.ai.edge.litertlm.Backend.CPU())
                    .also { it.ensureLoaded() }
            }
        }
        try {
            val pipeline = OnDevicePipeline(engine, transport, repo.categoryNames())
            val t0 = System.currentTimeMillis()
            when (val r = pipeline.extract(pixels, lastW, lastH, fallbackYear = java.time.Year.now().value)) {
                is ExtractResult.Success -> {
                    val total = System.currentTimeMillis() - t0
                    Log.i(TAG, "SMOKE_LITERT_E2E_OK drafts=${r.drafts.size} ocrMs=${r.times.ocrMs} llmMs=${r.times.llmMs} totalMs=$total")
                    r.drafts.forEachIndexed { i, d ->
                        Log.i(TAG, "SMOKE_LITERT_DRAFT[$i] datePaid=${d.datePaid} amount=${d.amountPaid} merchant=${d.merchant} category=${d.category}")
                    }
                    e2eResult.value = r.drafts.joinToString("\n\n") { d -> "⚡ ${d.datePaid}\n¥${d.amountPaid}  ${d.category}\n${d.merchant}" }
                    status.value = "LiteRT 端到端完成：${r.drafts.size} 单，总 ${total}ms（OCR ${r.times.ocrMs}ms / LLM ${r.times.llmMs}ms）"
                    if (r.drafts.isNotEmpty()) {
                        navigate(Page.Confirm(r.drafts[0], lastPhotoPath))
                    }
                }
                is ExtractResult.Failure -> {
                    Log.e(TAG, "SMOKE_LITERT_E2E_FAIL reason=${r.reason}")
                    e2eResult.value = "❌ ${r.reason}"
                    status.value = "LiteRT 端到端失败（见下方原因）"
                }
            }
        } finally {
            transport.close()
        }
    }

    private suspend fun runE2e(pixels: IntArray?): Unit = withContext(Dispatchers.Default) {
        if (pixels == null) {
            status.value = "先 1.选截图"
            return@withContext
        }
        if (!checkMemoryGate()) return@withContext
        if (!llmReady || ctxPtr == 0L) {
            val ok = loadLlm()
            if (!ok) return@withContext
        }

        val engine = ocrEngine ?: OcrEngine(
            detModel = File(detPath ?: return@withContext.also { status.value = "缺 det.onnx" }),
            recModel = File(recPath ?: return@withContext.also { status.value = "缺 rec.onnx" }),
            clsModel = clsPath?.let(::File),
            threads = 4,
        ).also { ocrEngine = it }

        status.value = "端到端提取中（约 3-4 分钟）…"
        val pipeline = OnDevicePipeline(engine, JniLlmTransport({ ctxPtr }), repo.categoryNames())
        val t0 = System.currentTimeMillis()
        when (val r = pipeline.extract(pixels, lastW, lastH, fallbackYear = java.time.Year.now().value)) {
            is ExtractResult.Success -> {
                val total = System.currentTimeMillis() - t0
                val times = r.times
                Log.i(TAG, "SMOKE_E2E_OK drafts=${r.drafts.size} ocrMs=${times.ocrMs} postMs=${times.postMs} llmMs=${times.llmMs} totalMs=$total")
                when {
                    r.drafts.isEmpty() -> {
                        e2eResult.value = "识别到文本但未找到订单块"
                        status.value = "未找到订单（无实付款特征）"
                    }
                    r.drafts.size == 1 -> {
                        // 单 Draft → 直接进确认界面（票 06）
                        val d = r.drafts[0]
                        Log.i(TAG, "SMOKE_E2E_DRAFT[0] datePaid=${d.datePaid} amount=${d.amountPaid} merchant=${d.merchant} category=${d.category}")
                        e2eResult.value = "✅ ${d.datePaid}\n¥${d.amountPaid}  ${d.category}\n${d.merchant}"
                        navigate(Page.Confirm(d, lastPhotoPath))
                        status.value = "提取完成，请确认入账"
                    }
                    else -> {
                        // 多 Draft（一图多单）：v1 取第一单进确认，其余留日志（票 07 队列细化）
                        val d = r.drafts[0]
                        r.drafts.forEachIndexed { i, dd ->
                            Log.i(TAG, "SMOKE_E2E_DRAFT[$i] datePaid=${dd.datePaid} amount=${dd.amountPaid} merchant=${dd.merchant} category=${dd.category}")
                        }
                        e2eResult.value = "共 ${r.drafts.size} 单，第一单待确认（其余见 logcat）"
                        navigate(Page.Confirm(d, lastPhotoPath))
                    }
                }
            }
            is ExtractResult.Failure -> {
                Log.e(TAG, "SMOKE_E2E_FAIL reason=${r.reason}")
                e2eResult.value = "❌ ${r.reason}"
                status.value = "端到端失败（见下方原因）"
            }
        }
    }

    // ---- 模型目录扫描与路径解析 ----

    private fun scanModelDir() {
        val dir = pushDir
        if (!dir.isDirectory) {
            status.value = "模型目录不存在\n${dir.absolutePath}\n(先 adb push)"
            return
        }
        var gguf: File? = null
        for (f in dir.listFiles().orEmpty()) {
            when {
                f.name.startsWith("ch_PP-OCRv4_det") -> detPath = f.absolutePath
                f.name.startsWith("ch_PP-OCRv4_rec") -> recPath = f.absolutePath
                f.name.startsWith("ch_ppocr_mobile") -> clsPath = f.absolutePath
                f.name.endsWith(".gguf") && (gguf == null || preferredOver(f, gguf)) -> gguf = f
                f.name.endsWith(".litertlm") -> litertlmPath = f.absolutePath // 票 14：多档并存取最后一个（推哪档用哪档）
            }
        }
        ggufPath = gguf?.absolutePath
    }

    /** 扫描到多个 gguf 时的选择：Q4_K_M 优先（票 10 速度优化主路线），同档取更大。 */
    private fun preferredOver(candidate: File, current: File): Boolean {
        val q4 = "Q4_K_M" in candidate.name
        val curQ4 = "Q4_K_M" in current.name
        return when {
            q4 && !curQ4 -> true
            !q4 && curQ4 -> false
            else -> candidate.length() > current.length()
        }
    }

    /** /sdcard/Android/data/<pkg>/files/X.ext → pushDir/X.ext（绕 scoped storage 的 EACCES）。 */
    private fun resolveUnderPushDir(path: String): String {
        val name = path.substringAfterLast('/')
        val inPushDir = File(pushDir, name)
        return if (inPushDir.exists()) inPushDir.absolutePath else path
    }

    // ---- SAF 选择器 ----

    /** 票 10：相册导入前守卫——无模型直接带去模型页，不进队列白等失败。 */
    private fun launchGalleryPick() {
        if (litertlmPath?.let(::File)?.takeIf(File::exists) == null) {
            importMsg = "还没有识别模型——先下载或导入，再导入截图"
            navigate(Page.ModelManage)
            return
        }
        pickMultipleImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** 票 07：相册多选 → 导入队列。 */
    private val pickMultipleImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris ->
            enqueueImports(uris.map { it to (it.lastPathSegment ?: "截图") })
        }

    // ---- 模型管理（票 10）----

    private fun startModelDownload() {
        if (downloadJob?.isActive == true) return
        val source = ModelManager.SOURCES[selectedSource]
        downloadState = ModelManager.DownloadState.Running(0, -1)
        downloadJob = lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    ModelManager.download(
                        pushDir, source.url, ModelManager.MODEL_NAME,
                        isCancelled = { downloadJob?.isCancelled == true },
                    ) { b, t ->
                        downloadState = ModelManager.DownloadState.Running(b, t)
                    }
                }
                scanModelDir()
                modelPrefs.edit().putBoolean("onboard_done", true).apply()
                downloadState = ModelManager.DownloadState.Idle
                importMsg = "模型下载完成：${file.name}"
                Log.i(TAG, "MODEL_DOWNLOAD_DONE ${file.name}")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    downloadState = ModelManager.DownloadState.Idle
                } else {
                    Log.e(TAG, "MODEL_DOWNLOAD_FAIL", t)
                    downloadState = ModelManager.DownloadState.Failed(t.message ?: t.toString())
                }
            }
        }
    }

    private fun cancelModelDownload() {
        // 只 cancel 不清引用：下载循环的取消检查读 downloadJob?.isCancelled，
        // 置 null 会让检查恒为 false，循环停不下来（真机翻过车）
        downloadJob?.cancel()
        downloadState = ModelManager.DownloadState.Idle
    }

    /** OCR 三件套下载（票 10 补充）：RapidOCR ModelScope 公开源，三个小文件顺序下。 */
    private var ocrDownloadState by mutableStateOf<ModelManager.DownloadState>(ModelManager.DownloadState.Idle)
    private var ocrDownloadJob: kotlinx.coroutines.Job? = null

    private fun startOcrDownload() {
        if (ocrDownloadJob?.isActive == true) return
        ocrDownloadState = ModelManager.DownloadState.Running(0, ModelManager.OCR_TOTAL_BYTES)
        ocrDownloadJob = lifecycleScope.launch {
            try {
                var doneBytes = 0L
                for (f in ModelManager.OCR_FILES) {
                    val base = doneBytes
                    withContext(Dispatchers.IO) {
                        ModelManager.download(
                            pushDir, f.url, f.targetName,
                            isCancelled = { ocrDownloadJob?.isCancelled == true },
                        ) { b, _ ->
                            ocrDownloadState = ModelManager.DownloadState.Running(base + b, ModelManager.OCR_TOTAL_BYTES)
                        }
                    }
                    doneBytes += File(pushDir, f.targetName).length()
                }
                scanModelDir()
                ocrDownloadState = ModelManager.DownloadState.Idle
                importMsg = "OCR 模型下载完成"
                Log.i(TAG, "OCR_DOWNLOAD_DONE")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    ocrDownloadState = ModelManager.DownloadState.Idle
                } else {
                    Log.e(TAG, "OCR_DOWNLOAD_FAIL", t)
                    ocrDownloadState = ModelManager.DownloadState.Failed(t.message ?: t.toString())
                }
            }
        }
    }

    private fun cancelOcrDownload() {
        ocrDownloadJob?.cancel()
        ocrDownloadState = ModelManager.DownloadState.Idle
    }

    /** 下载不可用时的兜底：文件管理器选取 .litertlm 拷入模型目录。 */
    private val pickModelFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModelUri(uri)
        }

    /** OCR 三件套无公开下载源，只能本地导入（多选 det/rec/cls ONNX）。 */
    private val pickOcrFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importOcrUris(uris)
        }

    private fun importModelUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val name = queryDisplayName(uri) ?: ModelManager.MODEL_NAME
                val target = File(pushDir, if (name.endsWith(".litertlm")) name else "$name.litertlm")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法打开所选文件")
                }
                scanModelDir()
                modelPrefs.edit().putBoolean("onboard_done", true).apply()
                importMsg = "已导入 ${target.name}（${"%.2f".format(target.length() / 1e9)} GB）"
            } catch (t: Throwable) {
                Log.e(TAG, "MODEL_IMPORT_FAIL", t)
                importMsg = "导入失败：${t.message}"
            }
        }
    }

    private fun importOcrUris(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                var copied = 0
                withContext(Dispatchers.IO) {
                    for (u in uris) {
                        val name = queryDisplayName(u) ?: continue
                        contentResolver.openInputStream(u)?.use { input ->
                            File(pushDir, name).outputStream().use { input.copyTo(it) }
                        } ?: continue
                        copied++
                    }
                }
                scanModelDir()
                importMsg = "已复制 $copied 个文件：det ${if (detPath != null) "✓" else "✗"} " +
                    "rec ${if (recPath != null) "✓" else "✗"} cls ${if (clsPath != null) "✓" else "✗"}"
            } catch (t: Throwable) {
                Log.e(TAG, "OCR_IMPORT_FAIL", t)
                importMsg = "OCR 导入失败：${t.message}"
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ---- 备份与导出（票 11）：SAF 出入，无存储权限 ----

    private val exportBackupDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) writeBackupTo(uri)
        }

    private val exportCsvDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) writeCsvTo(uri)
        }

    private val importBackupDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                pendingRestoreUri = uri
                showRestoreConfirm = true
            }
        }

    private fun stamp(): String =
        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

    private fun writeBackupTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (entryCount, photoCount) = withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { repo.exportBackupZip(it) }
                        ?: error("无法写入所选位置")
                }
                backupMsg = "备份已导出（$entryCount 条账目 / $photoCount 张照片）"
                Log.i(TAG, "BACKUP_EXPORTED entries=$entryCount photos=$photoCount")
            } catch (t: Throwable) {
                Log.e(TAG, "BACKUP_EXPORT_FAIL", t)
                backupMsg = "备份导出失败：${t.message}"
            }
        }
    }

    private fun writeCsvTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (entries, _) = repo.snapshot()
                val csv = withContext(Dispatchers.IO) { BackupCodec.toCsv(entries) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法写入所选位置")
                }
                backupMsg = "CSV 已导出（${entries.size} 条账目，Excel 可直接打开）"
                Log.i(TAG, "CSV_EXPORTED count=${entries.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "CSV_EXPORT_FAIL", t)
                backupMsg = "CSV 导出失败：${t.message}"
            }
        }
    }

    private fun restoreFromBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                }
                // 文件头 PK = zip 完整备份（含照片）；否则按票 11 旧版 JSON（无照片）恢复
                val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                val msg: String = if (isZip) {
                    val contents = withContext(Dispatchers.IO) {
                        BackupZip.read(bytes.inputStream())
                    }
                    withContext(Dispatchers.IO) {
                        repo.restoreBackupZip(contents.json, contents.photos, contents.thumbs)
                    }
                    val data = BackupCodec.decode(contents.json)
                    "已恢复 ${data.entries.size} 条账目 / ${data.categories.size} 个类别 / " +
                        "${contents.photos.size} 张照片（备份时间 ${data.exportedAt}）"
                } else {
                    val text = bytes.toString(Charsets.UTF_8)
                    val data = withContext(Dispatchers.IO) { BackupCodec.decode(text) }
                    repo.restore(data)
                    "已恢复 ${data.entries.size} 条账目 / ${data.categories.size} 个类别" +
                        "（旧版备份，无照片；备份时间 ${data.exportedAt}）"
                }
                backupMsg = msg
                Log.i(TAG, "BACKUP_RESTORED zip=$isZip")
            } catch (t: Throwable) {
                Log.e(TAG, "BACKUP_RESTORE_FAIL", t)
                backupMsg = "恢复失败：${t.message}"
            }
        }
    }


    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    // SAF URI 读原始字节（入账原图）+ 解码像素（OCR 输入）
                    withContext(Dispatchers.IO) {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes == null) {
                            status.value = "读图失败"
                            return@withContext
                        }
                        lastPhotoBytes = bytes
                        lastPhotoPath = null  // SAF 源无文件路径，入账走字节流
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp == null) {
                            status.value = "解码失败"
                            return@withContext
                        }
                        val p = IntArray(bmp.width * bmp.height)
                        bmp.getPixels(p, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                        lastW = bmp.width; lastH = bmp.height
                        bmp.recycle()
                        lastPixels = p
                        imageReady.value = true
                        status.value = "图就绪 ${lastW}x${lastH}，点 2.OCR 或 5.端到端"
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "decode image failed", t)
                    status.value = "读图失败：${t.message}"
                }
            }
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            when {
                llmReady || ggufPath != null -> lifecycleScope.launch { loadLlm(ggufPath) }
                uri != null -> {
                    try {
                        val pfd = contentResolver.openFileDescriptor(uri, "r")
                            ?: error("无法打开 $uri")
                        modelFd = pfd
                        lifecycleScope.launch { loadLlm("/proc/self/fd/${pfd.fd}") }
                    } catch (t: Throwable) {
                        Log.e(TAG, "open model fd failed", t)
                        status.value = "打开模型失败：${t.message}"
                    }
                }
                else -> status.value = "无 GGUF（push 到 ${pushDir.absolutePath} 或 SAF 选择）"
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ocrEngine?.close() }
        if (ctxPtr != 0L) runCatching { LlamaNative.freeContext(ctxPtr) }
        if (modelPtr != 0L) runCatching { LlamaNative.freeModel(modelPtr) }
        runCatching { LlamaNative.backendFree() }
        runCatching { modelFd?.close() }
    }
}
