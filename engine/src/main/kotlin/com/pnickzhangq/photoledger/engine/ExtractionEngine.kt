package com.pnickzhangq.photoledger.engine

/**
 * 提取引擎门面（spec 模块划分「提取引擎 (Extraction Engine)」）：
 * 输入位图 + 类别配置，输出 [Draft]。
 * 内部串联 prompt 构造 → grammar 生成 → LLM 调用 → JSON 解析与口径归一。
 */
class ExtractionEngine(
    private val transport: LlmTransport,
    private val categories: List<String>,
) {
    init {
        require(categories.isNotEmpty()) { "categories 不能为空" }
    }

    private val schema = ExtractionSchema(categories)
    private val grammar = GrammarGenerator.fromSchema(schema)

    suspend fun extract(imageData: ByteArray, imageMime: String): Draft {
        val prompt = PromptBuilder.build(categories)
        val raw = transport.complete(imageData, imageMime, prompt, grammar)
        return DraftNormalizer.parse(raw, categories)
    }

    /**
     * OCR 路线（票 12）：后处理过的 OCR 行 → OCR 版 prompt → 文本模型 + GBNF。
     * 一图多单时返回多个 Draft（与 VLM 路线的单 Draft 签名区分）。
     *
     * 日期约束：候选清单存在时把「日期选择」编码进文法（模型只能从后处理认可的字面量中选），
     * 消除 0.6B 自拼畸形日期（0909-09-09 类）的问题。清单为空时用 fallbackYear 构造
     * 「MM-DD 补全」候选；连 fallbackYear 都没有才退化为自由日期字段（且模型仍可能拼错，
     * 该分支预期被上层保证不触发——见 CLI 传入当前年份）。
     */
    suspend fun extractFromOcr(
        lines: List<OcrLine>,
        fallbackYear: Int? = null,
    ): List<Draft> {
        val blocks = OcrPostProcessor.splitOrderBlocks(lines)
        val year = OcrPostProcessor.guessContextYear(lines) ?: fallbackYear
        return blocks.map { block ->
            val material = OcrPostProcessor.buildStructuredMaterial(block, year)
            // 票 07 提速：日期只到天——候选与 prompt 清单都截到 YYYY-MM-DD（去重）
            val dateOnly = material.normalizedDates.map { it.take(10) }.distinct()
            val prompt = PromptBuilder.buildFromOcr(categories, material.blockText, dateOnly, material.amounts)
            val dateGrammar = if (dateOnly.isEmpty()) grammar
            else GrammarGenerator.withDateAlternatives(grammar, dateOnly)
            val raw = transport.completeText(prompt, dateGrammar)
            DraftNormalizer.parse(raw, categories)
        }
    }
}
