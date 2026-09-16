package com.pnickzhangq.photoledger.cli

import com.pnickzhangq.photoledger.engine.ExtractionEngine
import com.pnickzhangq.photoledger.engine.GateScoring
import com.pnickzhangq.photoledger.engine.OcrClient
import com.pnickzhangq.photoledger.engine.OcrLine
import com.pnickzhangq.photoledger.engine.llamacpp.LlamaServerProcess
import com.pnickzhangq.photoledger.engine.llamacpp.LlamaServerTransport
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 质量闸门 harness（票 02）：
 * gradle :cli:run --args="--gate <标注.csv> [--images 目录] [--model 路径] [--out 报告.md]"
 *
 * 标注 CSV 格式（fixtures/orders.csv）：文件名,日期,实付款(元)   [+可选列: 商家,类别]
 * 一条命令产出逐字段正确率报告 + 闸门结论（PASS/FAIL + 失败明细）。
 * 换模型/改 prompt 后重跑同一命令即可对比（S1 接缝可重复性）。
 */
fun runGate(
    annotationCsv: File,
    imagesDir: File?,
    model: String,
    serverBinary: String,
    ocrWorker: String,
    port: Int,
    outPath: String?,
    transportKind: String = "llamacpp",   // 票 13：llamacpp | litert
) {
    val root = File(System.getProperty("user.dir")).let { dir ->
        generateSequence(dir) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: dir
    }
    val csv = if (annotationCsv.isAbsolute) annotationCsv else File(root, annotationCsv.path)
    require(csv.exists()) { "标注表不存在：$csv" }

    val annotations = csv.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
        val cols = line.split(",").map { it.trim() }
        Annotation(
            file = cols[0],
            date = cols[1],
            amount = cols[2].toDouble(),
            merchant = cols.getOrNull(3)?.takeIf { it.isNotEmpty() },
            category = cols.getOrNull(4)?.takeIf { it.isNotEmpty() },
        )
    }
    require(annotations.isNotEmpty()) { "标注表为空" }

    val imgDir = imagesDir ?: csv.parentFile
    val images = annotations.map { File(imgDir, it.file) }
    val missing = images.filter { !it.exists() }
    if (missing.isNotEmpty()) {
        System.err.println("缺失截图：${missing.map { it.name }}")
        error("数据集不完整：${missing.size} 张缺失")
    }

    println("数据集：${annotations.size} 张（标注 ${csv.name}）· 后端 $transportKind")

    runBlocking {
        if (transportKind == "litert") {
            LitertJvmTransport(
                modelPath = (File(root, model).takeIf { File(root, model).exists() } ?: File(model)).absolutePath,
                backend = com.google.ai.edge.litertlm.Backend.CPU(),
            ).use { transport ->
                runGateScoring(csv, images, annotations, outPath, transport, ocrWorker)
            }
            return@runBlocking
        }
        LlamaServerProcess(
            serverBinary = File(root, serverBinary).takeIf { File(root, serverBinary).exists() } ?: File(serverBinary),
            modelPath = (File(root, model).takeIf { File(root, model).exists() } ?: File(model)).absolutePath,
            mmprojPath = "", // OCR 路线纯文本
            port = port,
        ).use { server ->
            println("llama-server 启动中（${server.baseUrl}）…")
            server.start()
            println("模型就绪。")

            val engine = ExtractionEngine(LlamaServerTransport(server.baseUrl), DEFAULT_CATEGORIES)
            val ocr = OcrClient(
                (File(root, ocrWorker).takeIf { File(root, ocrWorker).exists() } ?: File(ocrWorker)).absolutePath,
                pythonBin = OCR_PYTHON,
            )

            // ---- OCR 批量 ----
            val t0 = System.currentTimeMillis()
            val ocrResults = ocr.recognize(images)
            val ocrByImage = ocrResults.associateBy { File(it.image).name }
            println("OCR 完成（${(System.currentTimeMillis() - t0) / 1000}s）")

            // ---- 逐图提取 + 评分 ----
            val scores = mutableListOf<GateScoring.ImageScore>()
            val detailLines = mutableListOf<String>()
            var extractFailed = 0
            for ((index, ann) in annotations.withIndex()) {
                val ocrRes = ocrByImage[ann.file] ?: error("OCR 缺失 ${ann.file}")
                val drafts = if (!ocrRes.ok || ocrRes.lines.isEmpty()) {
                    extractFailed++
                    emptyList()
                } else {
                    try {
                        val lines = ocrRes.lines.map { OcrLine(it.text, it.score, it.box.flatten()) }
                        val fallbackYear = Regex("""(20\d{2})""").find(ann.file)?.groupValues?.get(1)?.toInt()
                            ?: Regex("""(20\d{2})""").find(ann.date)?.groupValues?.get(1)?.toInt()
                            ?: LocalDate.now().year
                        engine.extractFromOcr(lines, fallbackYear)
                    } catch (e: Exception) {
                        extractFailed++
                        System.err.println("[提取失败] ${ann.file}: ${e.message?.take(120)}")
                        emptyList()
                    }
                }

            val score = GateScoring.scoreImage(
                file = ann.file,
                annotationAmount = ann.amount,
                annotationDate = ann.date,
                annotationMerchant = ann.merchant,
                annotationCategory = ann.category,
                drafts = drafts,
            )
            scores += score

            val draftsText = drafts.joinToString(" | ") { "${it.merchant} ¥${it.amountPaid} @${it.datePaid.take(10)} [${it.category}]" }
            val hit = if (score.hasMatch) "✓" else "✗"
            detailLines += "| ${ann.file} | ${ann.amount} | ${ann.date} | $hit | ${drafts.size}条 | $draftsText |"
            print("\r[${index + 1}/${annotations.size}]")
        }
        println()

        // ---- 汇总 ----
        val summary = GateScoring.summarize(scores)
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val report = buildString {
            appendLine("# 质量闸门报告（$ts）")
            appendLine()
            appendLine("- 数据集：${summary.totalImages} 张（标注 ${csv.name}），提取 Draft 共 ${summary.totalDrafts} 条，提取失败 $extractFailed 张")
            appendLine("- 硬字段：实付款 **${pct(summary.amountAccuracy)}**（阈值 95%）、日期 **${pct(summary.dateAccuracy)}**（阈值 95%）")
            appendLine("- 软字段：商家 **${pct(summary.merchantAccuracy)}**、类别 **${pct(summary.categoryAccuracy)}**（阈值 80%）")
            appendLine()
            appendLine("## 闸门结论：${if (summary.pass) "PASS ✅" else "FAIL ❌"}")
            appendLine()
            if (!summary.pass) {
                appendLine("失败字段分布：金额错 ${scores.count { it.amountCorrect == false }}、日期错 ${scores.count { it.dateCorrect == false }}、无匹配 ${scores.count { !it.hasMatch }}")
                appendLine()
            }
            appendLine("## 失败明细（金额或日期不正确）")
            appendLine()
            appendLine("| 文件 | 标注金额 | 标注日期 | 命中 | 拆单 | 提取 Draft |")
            appendLine("|---|---|---|---|---|---|")
            summary.failures.forEach { f ->
                appendLine(detailLines.first { it.startsWith("| ${f.file} ") })
            }
            appendLine()
            appendLine("## 全量明细")
            appendLine()
            appendLine("| 文件 | 标注金额 | 标注日期 | 命中 | 拆单 | 提取 Draft |")
            appendLine("|---|---|---|---|---|---|")
            detailLines.forEach { appendLine(it) }
        }
        val outFile = outPath?.let { if (File(it).isAbsolute) File(it) else File(root, it) }
            ?: File(root, "build/gate-report.md")
        outFile.parentFile?.mkdirs()
        outFile.writeText(report)
        println(report.lines().take(8).joinToString("\n"))
        println("报告已写入：${outFile.absolutePath}")
        }
    }
}

private fun pct(v: Double): String = "%.1f%%".format(v * 100)

/**
 * OCR + 逐图提取 + 评分 + 报告（票 13 从 runGate 抽出，llamacpp/litert 两种 transport 共用）。
 */
private suspend fun runGateScoring(
    csv: File,
    images: List<File>,
    annotations: List<Annotation>,
    outPath: String?,
    transport: com.pnickzhangq.photoledger.engine.LlmTransport,
    ocrWorker: String,
) {
    val root = File(System.getProperty("user.dir")).let { dir ->
        generateSequence(dir) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").exists() } ?: dir
    }
    val engine = ExtractionEngine(transport, DEFAULT_CATEGORIES)
    val ocr = OcrClient(
        (File(root, ocrWorker).takeIf { File(root, ocrWorker).exists() } ?: File(ocrWorker)).absolutePath,
        pythonBin = OCR_PYTHON,
    )

    // ---- OCR 批量 ----
    val t0 = System.currentTimeMillis()
    val ocrResults = ocr.recognize(images)
    val ocrByImage = ocrResults.associateBy { File(it.image).name }
    println("OCR 完成（${(System.currentTimeMillis() - t0) / 1000}s）")

    // ---- 逐图提取 + 评分 ----
    val scores = mutableListOf<GateScoring.ImageScore>()
    val detailLines = mutableListOf<String>()
    var extractFailed = 0
    for ((index, ann) in annotations.withIndex()) {
        val ocrRes = ocrByImage[ann.file] ?: error("OCR 缺失 ${ann.file}")
        val drafts = if (!ocrRes.ok || ocrRes.lines.isEmpty()) {
            extractFailed++
            emptyList()
        } else {
            try {
                val lines = ocrRes.lines.map { OcrLine(it.text, it.score, it.box.flatten()) }
                val fallbackYear = Regex("""(20\d{2})""").find(ann.file)?.groupValues?.get(1)?.toInt()
                    ?: Regex("""(20\d{2})""").find(ann.date)?.groupValues?.get(1)?.toInt()
                    ?: LocalDate.now().year
                engine.extractFromOcr(lines, fallbackYear)
            } catch (e: Exception) {
                extractFailed++
                System.err.println("[提取失败] ${ann.file}: ${e.message?.take(120)}")
                emptyList()
            }
        }

        val score = GateScoring.scoreImage(
            file = ann.file,
            annotationAmount = ann.amount,
            annotationDate = ann.date,
            annotationMerchant = ann.merchant,
            annotationCategory = ann.category,
            drafts = drafts,
        )
        scores += score

        val draftsText = drafts.joinToString(" | ") { "${it.merchant} ¥${it.amountPaid} @${it.datePaid.take(10)} [${it.category}]" }
        val hit = if (score.hasMatch) "✓" else "✗"
        detailLines += "| ${ann.file} | ${ann.amount} | ${ann.date} | $hit | ${drafts.size}条 | $draftsText |"
        print("\r[${index + 1}/${annotations.size}]")
    }
    println()

    // ---- 汇总 ----
    val summary = GateScoring.summarize(scores)
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    val report = buildString {
        appendLine("# 质量闸门报告（$ts）")
        appendLine()
        appendLine("- 数据集：${summary.totalImages} 张（标注 ${csv.name}），提取 Draft 共 ${summary.totalDrafts} 条，提取失败 $extractFailed 张")
        appendLine("- 硬字段：实付款 **${pct(summary.amountAccuracy)}**（阈值 95%）、日期 **${pct(summary.dateAccuracy)}**（阈值 95%）")
        appendLine("- 软字段：商家 **${pct(summary.merchantAccuracy)}**、类别 **${pct(summary.categoryAccuracy)}**（阈值 80%）")
        appendLine()
        appendLine("## 闸门结论：${if (summary.pass) "PASS ✅" else "FAIL ❌"}")
        appendLine()
        if (!summary.pass) {
            appendLine("失败字段分布：金额错 ${scores.count { it.amountCorrect == false }}、日期错 ${scores.count { it.dateCorrect == false }}、无匹配 ${scores.count { !it.hasMatch }}")
            appendLine()
        }
        appendLine("## 失败明细（金额或日期不正确）")
        appendLine()
        appendLine("| 文件 | 标注金额 | 标注日期 | 命中 | 拆单 | 提取 Draft |")
        appendLine("|---|---|---|---|---|---|")
        summary.failures.forEach { f ->
            appendLine(detailLines.first { it.startsWith("| ${f.file} ") })
        }
        appendLine()
        appendLine("## 全量明细")
        appendLine()
        appendLine("| 文件 | 标注金额 | 标注日期 | 命中 | 拆单 | 提取 Draft |")
        appendLine("|---|---|---|---|---|---|")
        detailLines.forEach { appendLine(it) }
    }
    val outFile = outPath?.let { if (File(it).isAbsolute) File(it) else File(root, it) }
        ?: File(root, "build/gate-report.md")
    outFile.parentFile?.mkdirs()
    outFile.writeText(report)
    println(report.lines().take(8).joinToString("\n"))
    println("报告已写入：${outFile.absolutePath}")
}

private const val OCR_WORKER_DEFAULT = "scripts/ocr_worker.py"

/** 标注表一行（fixtures/orders.csv）。 */
private data class Annotation(
    val file: String,
    val date: String,
    val amount: Double,
    val merchant: String?,
    val category: String?,
)

private fun List<List<Float>>.flatten(): List<Float> = flatMap { it }
