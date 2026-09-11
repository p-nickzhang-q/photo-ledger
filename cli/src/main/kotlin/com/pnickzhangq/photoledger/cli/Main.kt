package com.pnickzhangq.photoledger.cli

import com.pnickzhangq.photoledger.engine.ExtractionEngine
import com.pnickzhangq.photoledger.engine.llamacpp.LlamaServerProcess
import com.pnickzhangq.photoledger.engine.llamacpp.LlamaServerTransport
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private val DEFAULT_CATEGORIES = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")

/**
 * 桌面一条命令跑通提取管线（票 01 验收项）：
 *
 * gradle :cli:run --args="截图1.png [截图2.png ...] [--model 路径] [--mmproj 路径] [--server-binary 路径] [--port 8901]"
 *
 * 每张截图输出一行 Draft JSON；malformed 输出计 1 并继续下一张（不阻塞）。
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

    var model = "models/Qwen3VL-4B-Instruct-Q4_K_M.gguf"
    var mmproj = "models/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf"
    var serverBinary = "third_party/llama.cpp/build/bin/llama-server"
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
            "--model" -> model = args[++i]
            "--mmproj" -> mmproj = args[++i]
            "--server-binary" -> serverBinary = args[++i]
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
        mmprojPath = resolve(mmproj).absolutePath,
        port = port,
    ).use { server ->
        println("llama-server 启动中（${server.baseUrl}）…")
        server.start()
        println("模型就绪。")

        val engine = ExtractionEngine(
            transport = LlamaServerTransport(server.baseUrl),
            categories = DEFAULT_CATEGORIES,
        )

        var malformed = 0
        var ok = 0
        for (image in images) {
            val file = resolve(image)
            if (!file.exists()) {
                System.err.println("[MISS] $image 不存在")
                malformed++
                continue
            }
            val bytes = file.readBytes()
            val mime = guessMime(file)
            print("提取 $image … ")
            try {
                val draft = engine.extract(bytes, mime)
                ok++
                println("OK")
                println(draftToJson(draft))
            } catch (e: Exception) {
                malformed++
                println("MALFORMED/FAILED：${e.message?.take(160)}")
            }
        }

        println()
        println("=== 结果：$ok 成功，$malformed 失败/malformed，共 ${images.size} 张 ===")
        if (malformed > 0) exitProcess(1)
    }
}

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
        用法：cli <截图...> [--model 路径] [--mmproj 路径] [--server-binary 路径] [--port N]
        例：gradle :cli:run --args="fixtures/a.png fixtures/b.png"
        """.trimIndent(),
    )
}
