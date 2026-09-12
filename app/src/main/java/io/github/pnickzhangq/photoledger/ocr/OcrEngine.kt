// 票 04：端侧 OCR——PP-OCRv4 mobile ONNX 三件套（det/cls/rec），与桌面 RapidOCR 同款模型文件，
// ADR-0004 同构约束。前后处理参照 RapidOCR 的 det_db_postprocess / rec CTC decode，
// 截图场景文本全水平：det 后处理用连通域 + AABB，不做旋转矩形/透视变换。
package io.github.pnickzhangq.photoledger.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** 一行 OCR 结果：文本 + 置信度 + 四点多边形（与 OCR 子进程协议 v1 的 lines 语义一致）。 */
data class OcrLine(
    val text: String,
    val score: Float,
    val box: List<List<Float>>,
)

class OcrEngine(
    detModel: File,
    recModel: File,
    clsModel: File?,
    private val threads: Int = 4,
) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val clsSession: OrtSession?
    private val recDict: List<String>

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
        }
        detSession = env.createSession(detModel.absolutePath, opts)
        recSession = env.createSession(recModel.absolutePath, opts)
        clsSession = clsModel?.let { env.createSession(it.absolutePath, opts) }
        recDict = loadDictFromMetadata(recSession)
    }

    /** 对一张图（ARGB 像素）做检测 + 识别，返回文本行。cls 三件套可选（截图全正向，冒烟跳过）。 */
    fun run(pixels: IntArray, width: Int, height: Int): List<OcrLine> {
        val boxes = detectBoxes(pixels, width, height)
        val lines = mutableListOf<OcrLine>()
        for (box in boxes) {
            val crop = cropBox(pixels, width, height, box)
            val (text, score) = recognize(crop.first, crop.second.first, crop.second.second)
            if (score > 0.0f && text.isNotEmpty()) {
                lines.add(OcrLine(text, score, box))
            }
        }
        return lines
    }

    // ---------- det ----------

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** det：限边 960 → 32 倍数 → DB 概率图 → 连通域 AABB。 */
    private fun detectBoxes(pixels: IntArray, width: Int, height: Int): List<List<List<Float>>> {
        val ratio = 960f / max(width, height)
        val rw = max(32, (width * ratio).toInt())
        val rh = max(32, (height * ratio).toInt())
        val rw32 = ((rw + 31) / 32) * 32
        val rh32 = ((rh + 31) / 32) * 32

        val input = FloatBuffer.allocate(3 * rh32 * rw32)
        for (y in 0 until rh32) {
            for (x in 0 until rw32) {
                // 边缘 replicate（限边后目标尺寸与等比缩放差 1-2px 时用最近邻近似）
                val sx = min(x * width / rw32, width - 1)
                val sy = min(y * height / rh32, height - 1)
                val px = pixels[sy * width + sx]
                val idx = y * rw32 + x
                input.put(idx, ((px shr 16 and 0xFF) / 255f - mean[0]) / std[0])
                input.put(rh32 * rw32 + idx, ((px shr 8 and 0xFF) / 255f - mean[1]) / std[1])
                input.put(2 * rh32 * rw32 + idx, ((px and 0xFF) / 255f - mean[2]) / std[2])
            }
        }

        val shape = longArrayOf(1, 3, rh32.toLong(), rw32.toLong())
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            detSession.run(mapOf(detSession.inputNames.first() to tensor)).use { output ->
                // det 输出 [1,1,H,W]：Java 侧四层 Array，取 [0][0] 得 H x W 概率图
                @Suppress("UNCHECKED_CAST")
                val probMap = (output[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                return dbPostprocess(probMap, rw32, rh32, width, height)
            }
        }
    }

    /** DB postprocess：0.3 阈值 → 4 连通域 → 面积过滤 → AABB 还原原图坐标 + 扩边。 */
    private fun dbPostprocess(
        probMap: Array<FloatArray>,
        pw: Int,
        ph: Int,
        origW: Int,
        origH: Int,
    ): List<List<List<Float>>> {
        val bin = ByteArray(pw * ph)
        for (y in 0 until ph) {
            val row = probMap[y]
            for (x in 0 until pw) {
                bin[y * pw + x] = if (row[x] > 0.3f) 1 else 0
            }
        }

        val labels = IntArray(pw * ph)
        val stack = IntArray(pw * ph)
        var nextLabel = 0
        val boxes = mutableListOf<List<List<Float>>>()
        for (start in bin.indices) {
            if (bin[start] == 0.toByte() || labels[start] != 0) continue
            nextLabel++
            var sp = 0
            stack[sp++] = start
            labels[start] = nextLabel
            var minX = pw; var maxX = 0; var minY = ph; var maxY = 0
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % pw
                val y = p / pw
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && bin[p - 1] == 1.toByte() && labels[p - 1] == 0) { labels[p - 1] = nextLabel; stack[sp++] = p - 1 }
                if (x < pw - 1 && bin[p + 1] == 1.toByte() && labels[p + 1] == 0) { labels[p + 1] = nextLabel; stack[sp++] = p + 1 }
                if (y > 0 && bin[p - pw] == 1.toByte() && labels[p - pw] == 0) { labels[p - pw] = nextLabel; stack[sp++] = p - pw }
                if (y < ph - 1 && bin[p + pw] == 1.toByte() && labels[p + pw] == 0) { labels[p + pw] = nextLabel; stack[sp++] = p + pw }
            }
            // 噪声过滤（对应 RapidOCR min_size 3）
            if (count < 3 || (maxX - minX + 1) < 3 || (maxY - minY + 1) < 3) continue

            val scale = max(origW / pw.toFloat(), origH / ph.toFloat())
            val unclip = 1.5f
            val x0 = ((minX - unclip) * scale).coerceIn(0f, origW - 1f)
            val y0 = ((minY - unclip) * scale).coerceIn(0f, origH - 1f)
            val x1 = ((maxX + 1 + unclip) * scale).coerceIn(0f, origW - 1f)
            val y1 = ((maxY + 1 + unclip) * scale).coerceIn(0f, origH - 1f)
            boxes.add(listOf(
                listOf(x0, y0), listOf(x1, y0), listOf(x1, y1), listOf(x0, y1),
            ))
        }
        // 阅读顺序：按 y 分桶（20px）再按 x；截图场景足够
        boxes.sortWith(compareBy({ (it[0][1] / 20).toInt() }, { it[0][0] }))
        return boxes
    }

    // ---------- rec ----------

    /** 识别一个裁剪块：高归一 48，宽按比例到 32 倍数，CTC decode。 */
    private fun recognize(crop: IntArray, cropW: Int, cropH: Int): Pair<String, Float> {
        if (cropW < 2 || cropH < 2) return "" to 0f
        val w = max(16, (cropW * 48f / cropH).toInt())
        val w32 = ((w + 31) / 32) * 32
        val h48 = 48
        val resized = resizeTo(crop, cropW, cropH, w32, h48)

        val input = FloatBuffer.allocate(3 * h48 * w32)
        for (y in 0 until h48) {
            for (x in 0 until w32) {
                val px = resized[y * w32 + x]
                val idx = y * w32 + x
                input.put(idx, ((px shr 16 and 0xFF) / 255f - mean[0]) / std[0])
                input.put(h48 * w32 + idx, ((px shr 8 and 0xFF) / 255f - mean[1]) / std[1])
                input.put(2 * h48 * w32 + idx, ((px and 0xFF) / 255f - mean[2]) / std[2])
            }
        }
        val shape = longArrayOf(1, 3, h48.toLong(), w32.toLong())
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            recSession.run(mapOf(recSession.inputNames.first() to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val probs = output[0].value as Array<Array<FloatArray>>
                return ctcDecode(probs[0])
            }
        }
    }

    /**
     * CTC decode：逐帧 argmax，去重、去 blank（idx 0）。
     * 模型输出维度 6625 = blank(0) + dict(6623) + space(6624)，与 RapidOCR CTCLabelDecode
     * （blank insert 0、space append 末尾）同序。输出字符多为单字符；多字符（词表罕见）全 append。
     */
    private fun ctcDecode(frameProbs: Array<FloatArray>): Pair<String, Float> {
        val n = recDict.size + 2  // blank + dict + space
        val sb = StringBuilder()
        var lastIdx = -1
        var total = 0f
        var count = 0
        for (frame in frameProbs) {
            var best = 0
            var bestP = frame[0]
            for (i in 1 until min(frame.size, n)) {
                if (frame[i] > bestP) { bestP = frame[i]; best = i }
            }
            if (best != 0 && best != lastIdx) {
                sb.append(if (best == recDict.size + 1) " " else recDict[best - 1])
                total += bestP
                count++
            }
            lastIdx = best
        }
        val score = if (count > 0) total / count else 0f
        return sb.toString() to score
    }

    /** 字典读自 rec 模型 ONNX metadata 的 "character" 键（\n 分隔，RapidOCR 内嵌约定）。 */
    private fun loadDictFromMetadata(session: OrtSession): List<String> {
        val raw = session.metadata.getCustomMetadataValue("character")
            .orElse(null)
            ?: error("rec model missing 'character' metadata")
        // 与 Python splitlines 等价：只去掉末尾换行残留，不滤中间行（字典无空行，已验证 6623 行）
        var body = raw
        while (body.endsWith("\n")) body = body.substring(0, body.length - 1)
        return body.split("\n")
    }

    /** 最近邻缩放（冒烟阶段够用；后续票可换双线性）。 */
    private fun resizeTo(pixels: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = min(y * srcH / dstH, srcH - 1)
            for (x in 0 until dstW) {
                val sx = min(x * srcW / dstW, srcW - 1)
                out[y * dstW + x] = pixels[sy * srcW + sx]
            }
        }
        return out
    }

    /** 从原图按 AABB 裁剪（边界 clamp），返回像素与宽高。 */
    private fun cropBox(pixels: IntArray, width: Int, height: Int, box: List<List<Float>>): Pair<IntArray, Pair<Int, Int>> {
        val xs = box.map { it[0].toInt().coerceIn(0, width - 1) }
        val ys = box.map { it[1].toInt().coerceIn(0, height - 1) }
        val x0 = xs.min()
        val x1 = xs.max()
        val y0 = ys.min()
        val y1 = ys.max()
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val crop = IntArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(pixels, (y0 + y) * width + x0, crop, y * w, w)
        }
        return crop to (w to h)
    }

    override fun close() {
        detSession.close()
        recSession.close()
        clsSession?.close()
    }
}
