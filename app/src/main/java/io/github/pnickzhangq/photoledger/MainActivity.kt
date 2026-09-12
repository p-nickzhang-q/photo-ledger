// 票 04 冒烟：单 Activity，SAF 选图 → OCR 上屏；SAF 选 GGUF → 加载 → 一次式推理上屏。
// 模型目录约定（开发期）：adb push 到 /sdcard/Download/photoledger/（det/rec/cls onnx + gguf）。
package io.github.pnickzhangq.photoledger

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import io.github.pnickzhangq.photoledger.ocr.LlamaNative
import io.github.pnickzhangq.photoledger.ocr.OcrEngine
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "photoledger-smoke"
        // 开发期约定（票04）：adb push 模型到 App 专属外部目录（scoped storage 下免权限直读）
        //   /sdcard/Android/data/io.github.pnickzhangq.photoledger/files/
        //   ├─ ch_PP-OCRv4_det_infer.onnx / ch_PP-OCRv4_rec_infer.onnx / ch_ppocr_mobile_v2.0_cls_infer.onnx
        //   └─ *.gguf
        private const val PUSH_DIR = "/sdcard/Android/data/io.github.pnickzhangq.photoledger/files"
    }

    private val exec = Executors.newSingleThreadExecutor()

    private val status = mutableStateOf("票04冒烟：1.选截图 → 2.OCR → 3.选GGUF → 4.推理")
    private val ocrResult = mutableStateOf("")
    private val llmResult = mutableStateOf("")
    private val imageReady = mutableStateOf(false)
    private val llmReadyState = mutableStateOf(false)

    private var lastPixels: IntArray? = null
    private var lastW = 0
    private var lastH = 0

    private var detPath: String? = null
    private var recPath: String? = null
    private var clsPath: String? = null
    private var ggufPath: String? = null
    private var ocrEngine: OcrEngine? = null

    private var modelPtr = 0L
    private var ctxPtr = 0L
    private var llmReady = false
    private var modelFd: android.os.ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanModelDir()
        // 开发期冒烟入口（票04）：
        //   am start --es smoke_image <路径> [--ez smoke_llm true]
        // 传了路径就跳过 SAF 直接载图并自动跑 OCR；smoke_llm=true 时继续加载 GGUF 并推理。
        val smokeImage = intent?.getStringExtra("smoke_image")
        val smokeLlm = intent?.getBooleanExtra("smoke_llm", false) ?: false
        if (smokeImage != null) {
            loadBitmapFromPath(smokeImage)
            exec.execute {
                // 等 OCR 引擎就绪的信号由 runOcr 内部处理，这里直接顺序触发
                runOnUiThread { runOcr() }
                if (smokeLlm) {
                    // runOcr 是异步的；LLM 等 OCR 出结果后再触发（结果写 ocrResult）
                    awaitOcrThen {
                        ggufPath?.let { loadLlm(it) } ?: runOnUiThread {
                            status.value = "无 GGUF，跳过 LLM 冒烟"
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
                    imageReady = imageReady.value,
                    llmReady = llmReadyState.value,
                    onPickImage = { pickImage.launch("image/*") },
                    onOcr = { runOcr() },
                    onPickModel = { pickModel.launch("*/*") },
                    onLlm = { runLlm() },
                )
            }
        }
    }

    /** 冒烟辅助：等 OCR 出结果（ocrResult 不再是 READY 占位）后回调。 */
    private fun awaitOcrThen(action: () -> Unit) {
        exec.execute {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                val r = ocrResult.value
                if (r != "READY" && r.isNotBlank()) { action(); return@execute }
                Thread.sleep(200)
            }
            runOnUiThread { status.value = "等待 OCR 超时" }
        }
    }

    private fun loadBitmapFromPath(path: String) {
        try {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp == null) {
                status.value = "解码失败：$path"
                return
            }
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            lastPixels = px
            lastW = bmp.width
            lastH = bmp.height
            imageReady.value = true
            status.value = "图就绪 ${bmp.width}x${bmp.height}，点 2.OCR"
            bmp.recycle()
        } catch (t: Throwable) {
            Log.e(TAG, "decode image failed", t)
            status.value = "读图失败：${t.message}"
        }
    }

    private fun scanModelDir() {
        val dir = File(PUSH_DIR)
        if (!dir.isDirectory) {
            status.value = "票04冒烟：模型目录不存在\n$PUSH_DIR\n(先 adb push)"
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

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                if (bmp == null) {
                    status.value = "解码失败"
                    return@registerForActivityResult
                }
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                lastPixels = px
                lastW = bmp.width
                lastH = bmp.height
                imageReady.value = true
                status.value = "图就绪 ${bmp.width}x${bmp.height}，点 2.OCR"
                bmp.recycle()
            } catch (t: Throwable) {
                Log.e(TAG, "decode image failed", t)
                status.value = "读图失败：${t.message}"
            }
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            // 已自动扫到 gguf（adb push 到 App 专属目录）时直接加载。
            // SAF 兜底：content:// URI 经 openFileDescriptor 拿 fd，/proc/self/fd/N 即真实路径，
            // llama.cpp 直接 mmap，零复制（持有 pfd 引用防 fd 被 GC 关闭）。
            val src: String? = when {
                llmReady || ggufPath != null -> ggufPath
                uri != null -> {
                    try {
                        val pfd = contentResolver.openFileDescriptor(uri, "r")
                            ?: error("无法打开 $uri")
                        modelFd = pfd  // 模型使用期间保持 fd 打开
                        "/proc/self/fd/${pfd.fd}"
                    } catch (t: Throwable) {
                        Log.e(TAG, "open model fd failed", t)
                        runOnUiThread { status.value = "打开模型失败：${t.message}" }
                        return@registerForActivityResult
                    }
                }
                else -> null
            }
            if (src == null) {
                status.value = "无 GGUF（push 到 $PUSH_DIR 或 SAF 选择）"
                return@registerForActivityResult
            }
            loadLlm(src)
        }

    private fun loadLlm(path: String) {
        exec.execute {
            try {
                val t0 = System.currentTimeMillis()
                LlamaNative.backendInit()
                modelPtr = LlamaNative.loadModel(path)
                ctxPtr = LlamaNative.newContext(modelPtr, nCtx = 2048)
                llmReady = true
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "SMOKE_LLM_LOAD_OK ms=$ms")
                runOnUiThread {
                    llmReadyState.value = true
                    status.value = "模型加载完成 ${ms}ms，点 4.推理"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "load model failed", t)
                runOnUiThread { status.value = "模型加载失败：${t.message}" }
            }
        }
    }

    private fun runOcr() {
        val px = lastPixels
        if (px == null || detPath == null || recPath == null) {
            status.value = "先选截图，且需 det/rec onnx 已 push 到 $PUSH_DIR"
            return
        }
        status.value = "OCR 运行中…"
        exec.execute {
            try {
                val engine = ocrEngine ?: OcrEngine(
                    detModel = File(detPath!!),
                    recModel = File(recPath!!),
                    clsModel = clsPath?.let(::File),
                    threads = 4,
                ).also { ocrEngine = it }
                val t0 = System.currentTimeMillis()
                val lines = engine.run(px, lastW, lastH)
                val ms = System.currentTimeMillis() - t0
                val text = if (lines.isEmpty()) "(no lines)" else lines.joinToString("\n") {
                    "(${it.box[0][0].toInt()},${it.box[0][1].toInt()}) [${"%.2f".format(it.score)}] ${it.text}"
                }
                runOnUiThread {
                    ocrResult.value = text
                    status.value = "OCR 完成：${lines.size} 行，${ms}ms"
                }
                // 冒烟验证信号（票04）：结果落 logcat，不依赖读屏
                Log.i(TAG, "SMOKE_OCR_OK lines=${lines.size} ms=$ms")
                for ((i, l) in lines.withIndex()) {
                    Log.i(TAG, "SMOKE_OCR_LINE[$i] box=(${l.box[0][0].toInt()},${l.box[0][1].toInt()}) score=${"%.2f".format(l.score)} text=${l.text}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ocr failed", t)
                runOnUiThread { status.value = "OCR 失败：${t.message}" }
            }
        }
    }

    private fun runLlm() {
        if (!llmReady || ctxPtr == 0L) {
            status.value = "先 3.选GGUF 加载模型"
            return
        }
        status.value = "推理运行中…"
        exec.execute {
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
                runOnUiThread {
                    llmResult.value = out.ifBlank { "(empty)" }
                    status.value = "推理完成 ${ms}ms"
                }
            } catch (t: Throwable) {
                Log.e(TAG, "llm failed", t)
                runOnUiThread { status.value = "推理失败：${t.message}" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
        runCatching { ocrEngine?.close() }
        if (ctxPtr != 0L) runCatching { LlamaNative.freeContext(ctxPtr) }
        if (modelPtr != 0L) runCatching { LlamaNative.freeModel(modelPtr) }
        runCatching { LlamaNative.backendFree() }
        // 模型释放后再关 fd（SAF 路径）
        runCatching { modelFd?.close() }
    }
}
