// 票 04 冒烟 + 票 05 端到端：选图 → OCR；扫 GGUF → 加载；端到端 → Draft 上屏。
// 并发层用协程（票04 重构）：顺序链路直接顺序写，无轮询无单线程池死锁；
// CPU 密集（OCR/LLM）走 Dispatchers.Default，Activity 销毁由 lifecycleScope 自动取消。
package io.github.pnickzhangq.photoledger

import android.app.ActivityManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import io.github.pnickzhangq.photoledger.ocr.ExtractResult
import io.github.pnickzhangq.photoledger.ocr.JniLlmTransport
import io.github.pnickzhangq.photoledger.ocr.LlamaNative
import io.github.pnickzhangq.photoledger.ocr.OcrEngine
import io.github.pnickzhangq.photoledger.ocr.OcrLine
import io.github.pnickzhangq.photoledger.ocr.OnDevicePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "photoledger-smoke"
        // 票 05 验收：内存门槛。0.6B Q8_0 峰值 ~0.7GB + OCR ~20MB + Kotlin/Compose 运行时 ~0.3GB，
        // 2GB 可用即安全；真机 11.7GB 正常空闲 4-6GB（实测 4.6GB 被旧门槛误拒）。
        private const val MIN_AVAILABLE_MB = 2048L
        // 与桌面 CLI DEFAULT_CATEGORIES 同源（零分叉）；后续票 08 类别体系时迁移共享常量
        private val CATEGORIES = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")
    }

    /** 开发期模型目录（票04）：adb push 到 App 专属外部目录。必须用 getExternalFilesDir()
     *  取路径——硬编码 /sdcard/Android/data/... 会被 scoped storage 拒（EACCES），
     *  而同一路径经 API 返回的 File 对 App 有完整读写权。 */
    private val pushDir: File
        get() = getExternalFilesDir(null) ?: filesDir

    // ---- UI 状态（Compose mutableStateOf）----
    private val status = mutableStateOf("照片记账（票05端到端）：1.选截图 → 2.OCR → 3.选GGUF → 4.推理 → 5.端到端")
    private val ocrResult = mutableStateOf("")
    private val llmResult = mutableStateOf("")
    private val e2eResult = mutableStateOf("")
    private val imageReady = mutableStateOf(false)
    private val llmReadyState = mutableStateOf(false)

    // ---- 已载入的图 ----
    private var lastPixels: IntArray? = null
    private var lastW = 0
    private var lastH = 0

    // ---- 模型路径（onCreate 扫描）----
    private var detPath: String? = null
    private var recPath: String? = null
    private var clsPath: String? = null
    private var ggufPath: String? = null

    // ---- 引擎与句柄（Default 线程访问）----
    private var ocrEngine: OcrEngine? = null
    private var modelPtr = 0L
    private var ctxPtr = 0L
    private var llmReady = false
    private var modelFd: android.os.ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanModelDir()

        // 开发期冒烟入口（票04/05）：
        //   --es smoke_image <路径> [--ez smoke_llm true] [--ez smoke_e2e true]
        // 结果全部落 logcat（SMOKE_*），不依赖读屏。
        val smokeImage = intent?.getStringExtra("smoke_image")
        val smokeLlm = intent?.getBooleanExtra("smoke_llm", false) ?: false
        val smokeE2e = intent?.getBooleanExtra("smoke_e2e", false) ?: false
        if (smokeImage != null) {
            lifecycleScope.launch {
                loadBitmap(resolveUnderPushDir(smokeImage))?.let { px ->
                    if (smokeE2e) {
                        // 端到端一条链：加载模型 → OCR+后处理+GBNF 提取 → Draft
                        val ok = loadLlm()
                        if (ok) runE2e(px)
                    } else {
                        val lines = runOcr(px)
                        if (smokeLlm && lines != null) {
                            val ok = loadLlm()
                            if (ok) runLlm()
                        }
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                SmokeScreen(
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
                )
            }
        }
    }

    // ---- 冒烟链路（协程，全部在 Dispatchers.Default 上跑 CPU/IO）----

    /** 读图并置 UI 状态；失败返回 null。 */
    private suspend fun loadBitmap(path: String): IntArray? = withContext(Dispatchers.IO) {
        try {
            val bmp = BitmapFactory.decodeFile(path)
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
                val text = if (lines.isEmpty()) "(no lines)" else lines.joinToString("\n") {
                    "(${it.box[0][0].toInt()},${it.box[0][1].toInt()}) [${"%.2f".format(it.score)}] ${it.text}"
                }
                ocrResult.value = text
                status.value = "OCR 完成：${lines.size} 行，${ms}ms"
                // 冒烟验证信号（票04）：结果落 logcat，不依赖读屏
                Log.i(TAG, "SMOKE_OCR_OK lines=${lines.size} ms=$ms")
                for ((i, l) in lines.withIndex()) {
                    Log.i(TAG, "SMOKE_OCR_LINE[$i] box=(${l.box[0][0].toInt()},${l.box[0][1].toInt()}) score=${"%.2f".format(l.score)} text=${l.text}")
                }
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
                ctxPtr = LlamaNative.newContext(modelPtr, nCtx = 2048)
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

    /** 一次式推理；结果写 UI/logcat。 */
    private suspend fun runLlm(): Unit = withContext(Dispatchers.Default) {
        if (!llmReady || ctxPtr == 0L) {
            status.value = "先 3.选GGUF 加载模型"
            return@withContext
        }
        status.value = "推理运行中…"
        try {
            val t0 = System.currentTimeMillis()
            val out = LlamaNative.complete(
                ctx = ctxPtr,
                prompt = "问：1+1=?\n答：",
                grammar = "",
                nLen = 32,
            )
            val ms = System.currentTimeMillis() - t0
            Log.i(TAG, "SMOKE_LLM_OK ms=$ms output=$out")
            llmResult.value = out.ifBlank { "(empty)" }
            status.value = "推理完成 ${ms}ms"
        } catch (t: Throwable) {
            Log.e(TAG, "llm failed", t)
            status.value = "推理失败：${t.message}"
        }
    }

    // ---- 票 05 端到端：截图 → Draft ----

    /** 内存门槛检查（票 05 验收）：可用 RAM 低于门槛报错而非加载后进低内存范围。 */
    private fun checkMemoryGate(): Boolean {
        val am = getSystemService(ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024L * 1024L)
        Log.i(TAG, "SMOKE_E2E_MEM availableMb=$availMb thresholdMb=$MIN_AVAILABLE_MB")
        if (availMb < MIN_AVAILABLE_MB) {
            status.value = "可用内存 ${availMb}MB 低于 ${MIN_AVAILABLE_MB}MB 门槛，" +
                "无法安全运行端侧提取（需要 ~1GB 模型内存）"
            Log.e(TAG, "SMOKE_E2E_REJECTED low memory ${availMb}MB")
            return false
        }
        return true
    }

    /**
     * 端到端提取：内存 gate → (模型未加载则先加载) → 共享引擎 OCR 路线 → Draft 上屏。
     * 分段耗时与 Draft 字段全部落 logcat（SMOKE_E2E_*）。
     */
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

        status.value = "端到端提取中（OCR+推理约 1-2 分钟）…"
        val pipeline = OnDevicePipeline(engine, JniLlmTransport({ ctxPtr }), CATEGORIES)
        val t0 = System.currentTimeMillis()
        when (val r = pipeline.extract(pixels, lastW, lastH, fallbackYear = java.time.Year.now().value)) {
            is ExtractResult.Success -> {
                val total = System.currentTimeMillis() - t0
                val times = r.times
                Log.i(TAG, "SMOKE_E2E_OK drafts=${r.drafts.size} ocrMs=${times.ocrMs} postMs=${times.postMs} llmMs=${times.llmMs} totalMs=$total")
                r.drafts.forEachIndexed { i, d ->
                    Log.i(TAG, "SMOKE_E2E_DRAFT[$i] datePaid=${d.datePaid} amount=${d.amountPaid} merchant=${d.merchant} category=${d.category}")
                }
                e2eResult.value = r.drafts.joinToString("\n\n") { d ->
                    "✅ ${d.datePaid}\n¥${d.amountPaid}  ${d.category}\n${d.merchant}"
                }
                status.value = "端到端完成：${r.drafts.size} 单，" +
                    "OCR ${times.ocrMs}ms / LLM ${times.llmMs}ms"
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
            status.value = "票04冒烟：模型目录不存在\n${dir.absolutePath}\n(先 adb push)"
            return
        }
        var gguf: String? = null
        for (f in dir.listFiles().orEmpty()) {
            when {
                f.name.startsWith("ch_PP-OCRv4_det") -> detPath = f.absolutePath
                f.name.startsWith("ch_PP-OCRv4_rec") -> recPath = f.absolutePath
                f.name.startsWith("ch_ppocr_mobile") -> clsPath = f.absolutePath
                f.name.endsWith(".gguf") && (gguf == null || f.length() > File(gguf).length()) -> gguf = f.absolutePath
            }
        }
        ggufPath = gguf
        val found = "det=${detPath != null} rec=${recPath != null} cls=${clsPath != null} gguf=${gguf != null}"
        status.value = "票04冒烟：1.选截图 → 2.OCR → 3.选GGUF(或自动) → 4.推理\n模型 [$found]"
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
                    // SAF URI 经 contentResolver 读流（IO），不落盘
                    val px = withContext(Dispatchers.IO) {
                        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        bmp?.let {
                            val p = IntArray(it.width * it.height)
                            it.getPixels(p, 0, it.width, 0, 0, it.width, it.height)
                            lastW = it.width; lastH = it.height
                            it.recycle()
                            p
                        }
                    }
                    if (px == null) {
                        status.value = "解码失败"
                    } else {
                        lastPixels = px
                        imageReady.value = true
                        status.value = "图就绪 ${lastW}x${lastH}，点 2.OCR"
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "decode image failed", t)
                    status.value = "读图失败：${t.message}"
                }
            }
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            // 已自动扫到 gguf（adb push 到 App 专属目录）时直接加载。
            // SAF 兜底：content:// URI 经 openFileDescriptor 拿 fd，/proc/self/fd/N 即真实路径，
            // llama.cpp 直接 mmap，零复制（持有 pfd 引用防 fd 被 GC 关闭）。
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
        // lifecycleScope 已随销毁取消；native 句柄在主线程释放（进程即将退出，与 Default 竞争窗口可接受——冒烟阶段）
        runCatching { ocrEngine?.close() }
        if (ctxPtr != 0L) runCatching { LlamaNative.freeContext(ctxPtr) }
        if (modelPtr != 0L) runCatching { LlamaNative.freeModel(modelPtr) }
        runCatching { LlamaNative.backendFree() }
        runCatching { modelFd?.close() }
    }
}
