package com.pnickzhangq.photoledger.cli

import com.pnickzhangq.photoledger.engine.ExtractionEngine
import com.pnickzhangq.photoledger.engine.OcrClient
import com.pnickzhangq.photoledger.engine.OcrLine
import com.pnickzhangq.photoledger.engine.llamacpp.LlamaServerProcess
import com.pnickzhangq.photoledger.engine.llamacpp.LlamaServerTransport
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private val DEFAULT_CATEGORIES = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")

/** RapidOCR 在专用 venv；桌面约定路径，可通过环境变量覆盖。 */
private val OCR_PYTHON = System.getenv("OCR_PYTHON") ?: "${System.getProperty("user.home")}/.ocr-venv/bin/python"

/**
 * 桌面一条命令跑通提取管线。
 *
 * gradle :cli:run --args="截图1.png [截图2.png ...] [--ocr|--vlm] [--model 路径] [--mmproj 路径] [--server-binary 路径] [--port 8901] [--ocr-worker 脚本]"
 *
 * 默认 --ocr（ADR-0004 主路线：RapidOCR 子进程 → 后处理 → 0.6B 文本模型 + GBNF）；
 * --vlm 为 01 票验证过的图像端到端路线（需 mmproj + VLM 模型），留作备选对比。
 * 每张截图输出 Draft JSON（OCR 路线下一图多单输出多行）；malformed 计数不阻塞后续。
 */
fun main(args: Array<String>) = runBlocking {
    if (args.isNotEmpty() && args[0] == "--dump-grammar") {
        dumpGrammar(args.getOrElse(1) { "build/extract-grammar.gbnf" })
        return@runBlocking
    }
    if (args.isEmpty()) {
        usage()
        return@runBlocking
    }

    var useVlm = false
    var model = if (useVlm) "models/Qwen3VL-4B-Instruct-Q4_K_M.gguf" else "models/Qwen3-0.6B-Q8_0.gguf"
    var mmproj = "models/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf"
    var serverBinary = "third_party/llama.cpp/build/bin/llama-server"
    var ocrWorker = "scripts/ocr_worker.py"
    var port = 8901
    val images = mutableListOf<String>()

    // gradle :cli:run 的工作目录是 cli/，相对路径统一解析到仓库根
    val projectRoot = File(System.getProperty("user.dir")).let { dir ->
        generateSequence(dir) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() }
            ?: dir
    }
    fun resolve(path: String): File = if (File(path).isAbsolute) File(path) else File(projectRoot, path)

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--ocr" -> useVlm = false
            "--vlm" -> useVlm = true
            "--model" -> model = args[++i]
            "--mmproj" -> mmproj = args[++i]
            "--server-binary" -> serverBinary = args[++i]
            "--ocr-worker" -> ocrWorker = args[++i]
            "--port" -> port = args[++i].toInt()
            else -> images += args[i]
        }
        i++
    }

    if (images.isEmpty()) {
        System.err.println("错误：至少要给一张截图路径")
        usage()
        exitProcess(2)
    }

    LlamaServerProcess(
        serverBinary = resolve(serverBinary),
        modelPath = resolve(model).absolutePath,
        mmprojPath = if (useVlm) resolve(mmproj).absolutePath else "",
        port = port,
    ).use { server ->
        println("llama-server 启动中（${server.baseUrl}，${if (useVlm) "VLM" else "OCR+文本"} 路线）…")
        server.start()
        println("模型就绪。")

        val engine = ExtractionEngine(
            transport = LlamaServerTransport(server.baseUrl),
            categories = DEFAULT_CATEGORIES,
        )

        var malformed = 0
        var ok = 0
        if (useVlm) {
            for (image in images) {
                val file = resolve(image)
                if (!file.exists()) {
                    System.err.println("[MISS] $image 不存在")
                    malformed++
                    continue
                }
                print("提取 $image … ")
                try {
                    val draft = engine.extract(file.readBytes(), guessMime(file))
                    ok++
                    println("OK")
                    println(draftToJson(draft))
                } catch (e: Exception) {
                    malformed++
                    println("MALFORMED/FAILED：${e.message?.take(160)}")
                }
            }
        } else {
            val files = images.map { resolve(it) }
            val missing = files.filter { !it.exists() }
            missing.forEach { System.err.println("[MISS] $it 不存在") }
            malformed += missing.size

            val present = files.filter { it.exists() }
            val ocrResults = OcrClient(resolve(ocrWorker).absolutePath, pythonBin = OCR_PYTHON)
                .recognize(present)
            for (result in ocrResults) {
                print("提取 ${result.image} … ")
                if (!result.ok) {
                    malformed++
                    println("OCR-FAILED：${result.error?.take(160)}")
                    continue
                }
                if (result.lines.isEmpty()) {
                    malformed++
                    println("EMPTY：OCR 无文本（可能不是订单截图）")
                    continue
                }
                try {
                    val lines = result.lines.map {
                        OcrLine(it.text, it.score, it.box.flatten())
                    }
                    // 日期补全上下文：文件名形如 Screenshot_2026_0910_xxx 时取其年份，否则当前年
                    val fallbackYear = Regex("""(20\d{2})""").find(File(result.image).name)?.groupValues?.get(1)?.toInt()
                        ?: java.time.LocalDate.now().year
                    val drafts = engine.extractFromOcr(lines, fallbackYear)
                    ok++
                    println("OK（${drafts.size} 条）")
                    drafts.forEach { println(draftToJson(it)) }
                } catch (e: Exception) {
                    malformed++
                    println("MALFORMED/FAILED：${e.message?.take(160)}")
                }
            }
        }

        println()
        println("=== 结果：$ok 成功，$malformed 失败/malformed，共 ${images.size} 张 ===")
        if (malformed > 0) exitProcess(1)
    }
}

private fun List<List<Float>>.flatten(): List<Float> = flatMap { it }

private fun guessMime(file: File): String = when (file.name.substringAfterLast('.').lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

private fun draftToJson(draft: com.pnickzhangq.photoledger.engine.Draft): String {
    fun jstr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return buildString {
        append("{")
        append("\"merchant\":").append(jstr(draft.merchant)).append(",")
        append("\"amountPaid\":").append(draft.amountPaid).append(",")
        append("\"currency\":").append(jstr(draft.currency)).append(",")
        append("\"datePaid\":").append(jstr(draft.datePaid)).append(",")
        append("\"dateSource\":").append(jstr(draft.dateSource.name)).append(",")
        append("\"orderStatus\":").append(jstr(draft.orderStatus)).append(",")
        append("\"category\":").append(jstr(draft.category))
        append("}")
    }
}

private fun usage() {
    println(
        """
        用法：cli <截图...> [--ocr] [--vlm] [--model 路径] [--mmproj 路径] [--server-binary 路径] [--ocr-worker 脚本] [--port N]
        默认 --ocr（RapidOCR + 后处理 + 0.6B 文本模型，ADR-0004）；--vlm 为备选图像路线。
        例：gradle :cli:run --args="fixtures/a.png fixtures/b.png"
        """.trimIndent(),
    )
}
