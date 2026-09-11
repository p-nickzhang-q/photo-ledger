package com.pnickzhangq.photoledger.engine.llamacpp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * llama-server 子进程生命周期管理（桌面 POC 集成方式）。
 *
 * 集成方式结论（供票 04 复用）：llama.cpp 官方在 llama-server 上同时支持
 * libmtmd 多模态（image_url）与 response_format json_schema（GBNF 约束），
 * 组合可行性由本 POC 实证；Android 端如继续此路线可内嵌 llama-server 或换 JNI，
 * 引擎核心（engine 模块）不感知差异。
 */
class LlamaServerProcess(
    private val serverBinary: File,
    private val modelPath: String,
    private val mmprojPath: String,
    private val port: Int = 8901,
    private val nGpuLayers: Int = 0,
    private val threads: Int = Runtime.getRuntime().availableProcessors(),
) : AutoCloseable {

    private var process: Process? = null

    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * 启动服务并等待 /health 就绪。llama-server 加载 2.5GB 模型需数十秒，
     * 就绪前 /health 返回 503。
     */
    suspend fun start(timeoutSeconds: Long = 600) = withContext(Dispatchers.IO) {
        require(serverBinary.exists()) { "llama-server 不存在：${serverBinary.absolutePath}" }
        check(process == null) { "llama-server 已在运行" }

        val cmd = mutableListOf(
            serverBinary.absolutePath,
            "--model", modelPath,
            "--port", port.toString(),
            "--host", "127.0.0.1",
            "--threads", threads.toString(),
            "--ctx-size", "8192",
            "-ngl", nGpuLayers.toString(),
            "--no-webui",
        )
        // 纯文本路线无 mmproj（文本模型与视觉投影 n_embd 不匹配会让 llama-server 直接退出）
        if (mmprojPath.isNotBlank()) cmd += listOf("--mmproj", mmprojPath)

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)

        val p = pb.start()
        process = p
        // 吞掉 stdout 防止管道缓冲阻塞子进程；进程关闭时流会断开，属正常路径
        Thread {
            try {
                p.inputStream.readBytes()
            } catch (e: Exception) {
                // ignore：close() 触发的流关闭
            }
        }.apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                throw RuntimeException("llama-server 提前退出，exit=${p.exitValue()}")
            }
            if (healthOk()) return@withContext
            Thread.sleep(1000)
        }
        throw RuntimeException("llama-server 在 ${timeoutSeconds}s 内未就绪")
    }

    private fun healthOk(): Boolean = try {
        val conn = java.net.URI("$baseUrl/health").toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (e: Exception) {
        false
    }

    override fun close() {
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }
        process = null
    }
}
