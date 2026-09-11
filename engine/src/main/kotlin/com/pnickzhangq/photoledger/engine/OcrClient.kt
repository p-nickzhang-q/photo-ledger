package com.pnickzhangq.photoledger.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** OCR 结果行（协议 docs/design/ocr-protocol.md）。 */
@Serializable
data class OcrResultLine(
    val text: String,
    val score: Float,
    val box: List<List<Float>>,
)

/** 单图 OCR 结果：ok=false 时 error 有值；空白图 ok=true 且 lines 为空。 */
@Serializable
data class OcrImageResult(
    val image: String,
    val ok: Boolean,
    val lines: List<OcrResultLine> = emptyList(),
    val error: String? = null,
)

@Serializable
data class OcrResponse(
    val version: Int,
    val results: List<OcrImageResult>,
)

/** OCR 层故障（引擎崩溃/协议不合法）。单图 ImageDecodeError 不属此类，看 [OcrImageResult.ok]。 */
class OcrEngineException(message: String) : Exception(message)

/**
 * OCR 子进程客户端（协议 v1）。桌面子进程调用 Python worker；
 * 端侧（票 04）替换为进程内 ONNX Runtime 实现时，复用 [parseResponse] 的语义。
 */
class OcrClient(
    private val workerScript: String,
    private val pythonBin: String = "python3",
    private val timeoutSeconds: Long = 300,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 批量识别。单图失败不阻塞其余（体现在各 result.ok）。引擎级故障抛 [OcrEngineException]。 */
    fun recognize(images: List<File>, workDir: File = File.createTempFile("ocr", "").parentFile): List<OcrImageResult> {
        if (images.isEmpty()) return emptyList()
        val req = File.createTempFile("ocr-req", ".json", workDir)
        val resp = File.createTempFile("ocr-resp", ".json", workDir)
        try {
            req.writeText("""{"images":${images.joinToString(",", "[", "]") { "\"" + it.absolutePath.replace("\\", "\\\\") + "\"" }}}""")
            val proc = ProcessBuilder(pythonBin, workerScript, req.absolutePath, resp.absolutePath)
                .redirectErrorStream(false)
                .start()
            val finished = proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw OcrEngineException("OCR worker 超时（${timeoutSeconds}s）")
            }
            if (proc.exitValue() != 0) {
                val err = proc.errorStream.bufferedReader().readText().take(500)
                throw OcrEngineException("OCR worker 退出码 ${proc.exitValue()}: $err")
            }
            return parseResponse(resp.readText())
        } finally {
            req.delete()
            resp.delete()
        }
    }

    /** 协议解析（端侧复用：喂进 worker/进程内的输出字符串即可）。 */
    fun parseResponse(raw: String): List<OcrImageResult> {
        val doc = try {
            json.decodeFromString<OcrResponse>(raw)
        } catch (e: Exception) {
            throw OcrEngineException("OCR 协议解析失败：${raw.take(200)}")
        }
        if (doc.version != 1) throw OcrEngineException("OCR 协议版本不支持：${doc.version}")
        return doc.results
    }
}
