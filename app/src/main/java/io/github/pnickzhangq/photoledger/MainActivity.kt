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
import io.github.pnickzhangq.photoledger.data.Entry
import io.github.pnickzhangq.photoledger.data.ImportQueue
import io.github.pnickzhangq.photoledger.data.ImportState
import io.github.pnickzhangq.photoledger.data.LedgerDatabase
import io.github.pnickzhangq.photoledger.data.LedgerRepository
import io.github.pnickzhangq.photoledger.data.PhotoStore
import io.github.pnickzhangq.photoledger.ocr.ExtractResult
import io.github.pnickzhangq.photoledger.ocr.JniLlmTransport
import io.github.pnickzhangq.photoledger.ocr.LitertLlmTransport
import io.github.pnickzhangq.photoledger.ocr.LlamaNative
import io.github.pnickzhangq.photoledger.ocr.OcrEngine
import io.github.pnickzhangq.photoledger.ocr.OcrLine
import io.github.pnickzhangq.photoledger.ocr.OnDevicePipeline
import io.github.pnickzhangq.photoledger.ui.CategoryManageScreen
import io.github.pnickzhangq.photoledger.ui.DraftConfirmScreen
import io.github.pnickzhangq.photoledger.ui.DraftForm
import io.github.pnickzhangq.photoledger.ui.EntryEditScreen
import io.github.pnickzhangq.photoledger.ui.EntryForm
import io.github.pnickzhangq.photoledger.ui.ImportQueueScreen
import io.github.pnickzhangq.photoledger.ui.LedgerListScreen
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
    data object ImportQueue : Page()   // 票 07
    data object CategoryManage : Page() // 票 08
    data object Summary : Page()        // 票 09
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
    private val pageStack = androidx.compose.runtime.mutableStateListOf<Page>(Page.Ledger)
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

    // ---- 导入队列（票 07）——lifecycleScope 下懒建：需要 repo 就绪 ----
    private var importQueue: ImportQueue? = null
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
    private var litertlmPath: String? = null

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
                            IconButton(onClick = { navigate(Page.SmokeTools) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "工具")
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                when (current) {
                    // 主操作：相册选取照片进导入队列（手工记账已删，导入即全部入口）
                    is Page.Ledger -> FloatingActionButton(onClick = {
                        pickMultipleImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
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
                        emptyHint = "还没有账目\n\n点右下角 ➕ 从相册导入订单截图",
                        onEntryClick = { navigate(Page.Detail(it.id)) },
                        onImportFromGallery = { pickMultipleImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    is Page.ImportQueue -> ImportQueueScreen(
                        items = importQueue?.items?.collectAsState()?.value.orEmpty(),
                        onImportFromGallery = { pickMultipleImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onConfirmDraft = ::confirmFromQueue,
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
            editedOrderStatus = form.orderStatus,
            photoBytes = lastPhotoBytes,
        )
        lastPhotoBytes = null
        backToRoot()   // 确认流程终点：回流水列表
    }

    private suspend fun saveEdit(entry: Entry, form: EntryForm) {
        repo.edit(entry) {
            copy(
                merchant = form.merchant,
                amountPaid = form.amountPaid.toDoubleOrNull() ?: 0.0,
                datePaid = form.datePaid,
                category = form.category,
                orderStatus = form.orderStatus,
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
            ?: error("缺 .litertlm 模型")
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

    /** 队列 Done 项 → 直接入账（不经编辑页）。无日期截图（OCR 小角标没读出）按导入当天入账。 */
    private fun confirmFromQueue(item: io.github.pnickzhangq.photoledger.data.ImportItem) {
        val draft = (item.state as? ImportState.Done)?.drafts?.firstOrNull() ?: return
        val effective = if (draft.datePaid.isBlank()) {
            draft.copy(datePaid = java.time.LocalDate.now().toString())
        } else {
            draft
        }
        lifecycleScope.launch {
            repo.confirm(draft = effective, photoBytes = item.bytes)
            ensureImportQueue().markConfirmed(item)
        }
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

    /** 票 07：相册多选 → 导入队列。 */
    private val pickMultipleImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)) { uris ->
            enqueueImports(uris.map { it to (it.lastPathSegment ?: "截图") })
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
