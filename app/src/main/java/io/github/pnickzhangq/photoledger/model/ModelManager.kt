// 票 10：模型管理——.litertlm 模型的发现 / 下载（源可切换 + 断点续传）/ RAM 预检。
// 联网仅限模型下载（ADR-0003）；下载走 .part 中转，成功后原子改名，失败重试从已写字节续传。
package io.github.pnickzhangq.photoledger.model

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

class ModelManager(private val dir: File) {

    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Running(val bytes: Long, val totalBytes: Long) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    data class Source(val label: String, val url: String)

    /** OCR 三件套：目标名沿用设备既有的 `_infer` 命名；源是 RapidOCR 官方 ModelScope 仓库
     *  的 `_mobile` 文件（字节数与 `_infer` 版逐一核对一致，2026-09-20）。 */
    data class OcrFile(val targetName: String, val url: String)

    companion object {
        private const val RAPIDOCR_BASE = "https://modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv4"

        val OCR_FILES = listOf(
            OcrFile("ch_PP-OCRv4_det_infer.onnx", "$RAPIDOCR_BASE/det/ch_PP-OCRv4_det_mobile.onnx"),
            OcrFile("ch_PP-OCRv4_rec_infer.onnx", "$RAPIDOCR_BASE/rec/ch_PP-OCRv4_rec_mobile.onnx"),
            OcrFile("ch_ppocr_mobile_v2.0_cls_infer.onnx", "$RAPIDOCR_BASE/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx"),
        )
        const val OCR_TOTAL_BYTES = 16_188_007L   // det + rec + cls 实际字节数之和
        const val MODEL_NAME = "Qwen3-0.6B.litertlm"
        // 实测（ModelScope Content-Range，2026-09）：614,236,160 字节 ≈ 0.6GB。
        // 工单原文「2.8GB」是 llama.cpp GGUF 旧口径；LiteRT .litertlm 小得多。
        const val SIZE_HINT = "约 0.6GB"

        val SOURCES = listOf(
            Source("ModelScope（国内，推荐）", "https://modelscope.cn/models/litert-community/Qwen3-0.6B/resolve/master/$MODEL_NAME"),
            Source("HuggingFace 官方", "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/$MODEL_NAME"),
            Source("hf-mirror 镜像", "https://hf-mirror.com/litert-community/Qwen3-0.6B/resolve/main/$MODEL_NAME"),
        )

        /** 票 10 验收：总内存 <8GB 不给下载（0.6B 模型 + OCR + 运行时的安全余量）。 */
        const val MIN_RAM_BYTES = 8L * 1024 * 1024 * 1024

        fun totalRamBytes(context: Context): Long {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            return mi.totalMem
        }

        fun findModel(dir: File): File? {
            val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm") }.orEmpty()
            return files.firstOrNull { it.name == MODEL_NAME } ?: files.maxByOrNull { it.lastModified() }
        }

        fun hasPartialDownload(dir: File): Boolean = File(dir, "$MODEL_NAME.part").let { it.exists() && it.length() > 0 }

        /**
         * 下载到 dir/fileName（先写 fileName.part，完成后改名）。已有 .part 且服务端支持 Range
         * 则从已写字节续传；服务端忽略 Range 回 200 则删 .part 重来。
         * 手动逐跳跟重定向（ModelScope 302 到 CDN）——HttpURLConnection 自动跟随时
         * 不保证 Range 头透传，手动跟最稳。
         * isCancelled：阻塞式 read 不响应协程取消，调用方每轮传入取消检查，
         * 命中即断连抛 CancellationException（.part 保留，重试续传）。
         */
        fun download(
            dir: File,
            url: String,
            fileName: String,
            isCancelled: () -> Boolean = { false },
            onProgress: (bytes: Long, totalBytes: Long) -> Unit,
        ): File {
            val part = File(dir, "$fileName.part")
            val start = if (part.exists()) part.length() else 0L
            var current = url
            val conn: HttpURLConnection
            var hops = 0
            while (true) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = false
                if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: run { c.disconnect(); error("重定向缺失 Location（HTTP $code）") }
                    current = URL(URL(current), loc).toString()
                    c.disconnect()
                    if (++hops > 5) error("重定向次数过多")
                    continue
                }
                conn = c
                break
            }
            try {
                val code = conn.responseCode
                if (code != 200 && code != 206) error("HTTP $code")
                val resumable = code == 206
                val offset = if (resumable) start else 0L
                if (!resumable && part.exists()) part.delete()
                val total = conn.contentLengthLong.takeIf { it > 0 }?.let { it + offset } ?: -1L
                conn.inputStream.use { input ->
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(offset)
                        val buf = ByteArray(256 * 1024)
                        var written = offset
                        while (true) {
                            if (isCancelled()) {
                                raf.close()
                                conn.disconnect()
                                throw CancellationException("下载已取消")
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                        if (total > 0 && written < total) error("连接中断（已下 $written / $total，重试可续传）")
                    }
                }
            } finally {
                conn.disconnect()
            }
            val final = File(dir, fileName)
            if (!part.renameTo(final)) error("下载完成但改名失败：${part.absolutePath}")
            return final
        }
    }
}
