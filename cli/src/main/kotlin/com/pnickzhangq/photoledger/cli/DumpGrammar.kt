package com.pnickzhangq.photoledger.cli

import com.pnickzhangq.photoledger.engine.ExtractionSchema
import com.pnickzhangq.photoledger.engine.GrammarGenerator
import java.io.File

/**
 * 把 engine 生成的 GBNF 固化为文件，供桌面 Python 侧 OCR 原型复用（与 Kotlin 引擎同一份文法）。
 * 用法：gradle :cli:run --args="--dump-grammar <输出路径>"
 */
fun dumpGrammar(outPath: String) {
    val grammar = GrammarGenerator.fromSchema(
        ExtractionSchema(listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")),
    )
    File(outPath).writeText(grammar)
    println("grammar written to $outPath")
}
