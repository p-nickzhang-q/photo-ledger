// 票 04 冒烟 + 票 05 端到端 + 票 06 账目库与确认流。
// 界面导航：流水列表（主体）→ Draft 确认 / Entry 详情 / 手工新增；冒烟工具页保留为开发入口。
// 并发层协程（票04 重构）：CPU 密集（OCR/LLM）走 Dispatchers.Default，销毁由 lifecycleScope 取消。
package io.github.pnickzhangq.photoledger

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.pnickzhangq.photoledger.data.Entry
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
import io.github.pnickzhangq.photoledger.ui.DraftConfirmScreen
import io.github.pnickzhangq.photoledger.ui.DraftForm
import io.github.pnickzhangq.photoledger.ui.EntryEditScreen
import io.github.pnickzhangq.photoledger.ui.EntryForm
import io.github.pnickzhangq.photoledger.ui.LedgerListScreen
import io.github.pnickzhangq.photoledger.ui.ManualEntryScreen
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 页面导航（无路由库，sealed class 足够本规模）。 */
private sealed class Page {
    data object Ledger : Page()
    data object ManualAdd : Page()
    data class Confirm(val draft: Draft, val photoPath: String?) : Page()
    data class Detail(val entryId: Long) : Page()
    data object SmokeTools : Page()
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "photoledger-smoke"
        // 票 05 验收：内存门槛。0.6B Q4_K_M 峰值 ~0.5GB + OCR ~20MB + 运行时 ~0.3GB。
        private const val MIN_AVAILABLE_MB = 2048L
        // 与桌面 CLI DEFAULT_CATEGORIES 同源（零分叉）；票 08 类别体系时迁移共享常量
        private val CATEGORIES = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")
    }

    /** 开发期模型目录（票04）：adb push 到 App 专属外部目录。 */
    private val pushDir: File
        get() = getExternalFilesDir(null) ?: filesDir

    // ---- 账目库 ----
    private lateinit var repo: LedgerRepository
    private val page = mutableStateOf<Page>(Page.Ledger)

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

    // ---- 引擎与句柄 ----
    private var ocrEngine: OcrEngine? = null
    private var modelPtr = 0L
    private var ctxPtr = 0L
    private var llmReady = false
    private var modelFd: android.os.ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = LedgerRepository(
            LedgerDatabase.get(this).entryDao(),
            PhotoStore(File(filesDir, "ledger")),
        )
        scanModelDir()

        // 开发期冒烟入口（票04/05）：结果落 logcat（SMOKE_*），不依赖读屏。
        val smokeImage = intent?.getStringExtra("smoke_image")
        val smokeLlm = intent?.getBooleanExtra("smoke_llm", false) ?: false
        val smokeE2e = intent?.getBooleanExtra("smoke_e2e", false) ?: false
        val smokeE2eLitert = intent?.getBooleanExtra("smoke_e2e_litert", false) ?: false
        if (smokeImage != null) {
            lifecycleScope.launch {
                loadBitmap(resolveUnderPushDir(smokeImage))?.let { px ->
                    lastPhotoPath = resolveUnderPushDir(smokeImage)
                    when {
                        smokeE2eLitert -> runE2eLitert(px)   // 票 13：LiteRT 后端（页面状态需切到工具页可见）
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

        setContent {
            MaterialTheme {
                AppScaffold()
            }
        }
    }

    // ---- 顶层脚手架：列表页带 FAB（手工新增）+ 工具入口 ----

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun AppScaffold() {
        val current = page.value
        val entries by repo.entries.collectAsState(initial = emptyList())

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("照片记账") },
                    actions = {
                        IconButton(onClick = {
                            page.value = if (current is Page.SmokeTools) Page.Ledger else Page.SmokeTools
                        }) {
                            Icon(Icons.Filled.Settings, contentDescription = "工具")
                        }
                    },
                )
            },
            floatingActionButton = {
                if (current is Page.Ledger) {
                    FloatingActionButton(onClick = { page.value = Page.ManualAdd }) {
                        Icon(Icons.Filled.Add, contentDescription = "手工记账")
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (val p = current) {
                    is Page.Ledger -> LedgerListScreen(
                        entries = entries,
                        thumbDir = File(File(filesDir, "ledger"), "thumbs"),
                        emptyHint = "还没有账目\n\n右上角「工具」里跑端到端提取，\n或点右下角 ➕ 手工记账",
                        onEntryClick = { page.value = Page.Detail(it.id) },
                    )
                    is Page.ManualAdd -> ManualEntryScreen(
                        categories = CATEGORIES,
                        onSave = { form -> lifecycleScope.launch { saveManual(form) } },
                        onCancel = { page.value = Page.Ledger },
                    )
                    is Page.Confirm -> DraftConfirmScreen(
                        draft = p.draft,
                        photoPath = p.photoPath,
                        categories = CATEGORIES,
                        onConfirm = { form -> lifecycleScope.launch { confirmDraft(p, form) } },
                        onDiscard = { repo.discard(); page.value = Page.Ledger },
                    )
                    is Page.Detail -> {
                        val entry = entries.firstOrNull { it.id == p.entryId }
                        if (entry == null) {
                            Text("账目不存在（已删除？）", Modifier.padding(16.dp))
                            OutlinedButton(onClick = { page.value = Page.Ledger }, Modifier.padding(16.dp)) { Text("返回") }
                        } else {
                            EntryEditScreen(
                                entry = entry,
                                photoDir = File(File(filesDir, "ledger"), "photos"),
                                categories = CATEGORIES,
                                onSave = { form -> lifecycleScope.launch { saveEdit(entry, form) } },
                                onDelete = { alsoPhoto -> lifecycleScope.launch { repo.delete(entry, alsoPhoto); page.value = Page.Ledger } },
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
        page.value = Page.Ledger
    }

    private suspend fun saveManual(form: EntryForm) {
        repo.addManual(
            merchant = form.merchant,
            amountPaid = form.amountPaid.toDoubleOrNull() ?: 0.0,
            datePaid = form.datePaid,
            category = form.category,
            orderStatus = form.orderStatus,
        )
        page.value = Page.Ledger
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
        page.value = Page.Ledger
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
     * 票 13：LiteRT-LM 后端端到端（速度对照）。
     * 同一共享引擎管线，transport 换 LitertLlmTransport（GPU/OpenCL）；
     * 基准：vivo 同厂 GPU 580 prefill / 21 decode tok/s，预期端到端 ~5-10s。
     */
    private suspend fun runE2eLitert(pixels: IntArray?): Unit = withContext(Dispatchers.Default) {
        if (pixels == null) {
            status.value = "先 1.选截图"
            return@withContext
        }
        val modelFile = File(pushDir, "Qwen3-0.6B.litertlm")
        if (!modelFile.exists()) {
            status.value = "缺 ${modelFile.name}（adb push 到 ${pushDir.absolutePath}）"
            return@withContext
        }
        val engine = ocrEngine ?: OcrEngine(
            detModel = File(detPath ?: return@withContext.also { status.value = "缺 det.onnx" }),
            recModel = File(recPath ?: return@withContext.also { status.value = "缺 rec.onnx" }),
            clsModel = clsPath?.let(::File),
            threads = 4,
        ).also { ocrEngine = it }

        status.value = "LiteRT 端到端（CPU——GPU OpenCL 库真机不可用）…"
        // 票 13 实测：vivo 封闭 OpenCL（libOpenCL.so 不暴露给 App 沙箱），GPU 后端报
        // "Can not find OpenCL library on this device"。CPU 档公开基准 165/9 tok/s。
        val transport = LitertLlmTransport(modelPath = modelFile.absolutePath, backend = com.google.ai.edge.litertlm.Backend.CPU())
        try {
            val pipeline = OnDevicePipeline(engine, transport, CATEGORIES)
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
                        page.value = Page.Confirm(r.drafts[0], lastPhotoPath)
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
        val pipeline = OnDevicePipeline(engine, JniLlmTransport({ ctxPtr }), CATEGORIES)
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
                        page.value = Page.Confirm(d, lastPhotoPath)
                        status.value = "提取完成，请确认入账"
                    }
                    else -> {
                        // 多 Draft（一图多单）：v1 取第一单进确认，其余留日志（票 07 队列细化）
                        val d = r.drafts[0]
                        r.drafts.forEachIndexed { i, dd ->
                            Log.i(TAG, "SMOKE_E2E_DRAFT[$i] datePaid=${dd.datePaid} amount=${dd.amountPaid} merchant=${dd.merchant} category=${dd.category}")
                        }
                        e2eResult.value = "共 ${r.drafts.size} 单，第一单待确认（其余见 logcat）"
                        page.value = Page.Confirm(d, lastPhotoPath)
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
