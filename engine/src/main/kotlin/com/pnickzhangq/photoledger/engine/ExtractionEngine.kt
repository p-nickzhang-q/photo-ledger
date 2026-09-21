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
     *
     * 低置信行过滤（票 09 后真机回归）：score < [OcrPostProcessor.MIN_LINE_SCORE] 的行在进
     * prompt 前丢弃——状态栏碎片会带偏 0.6B 的金额选择（真机案例：0.57 的「794」被当成
     * 金额，370 元转账页提取成 794；重放差分验证过滤后取回 370）。
     */
    suspend fun extractFromOcr(
        lines: List<OcrLine>,
        fallbackYear: Int? = null,
    ): List<Draft> {
        val usable = lines.filter { it.score >= OcrPostProcessor.MIN_LINE_SCORE }
        if (usable.isEmpty()) return emptyList()
        val blocks = OcrPostProcessor.splitOrderBlocks(usable)
        val year = OcrPostProcessor.guessContextYear(usable) ?: fallbackYear
        return blocks.map { block ->
            val material = OcrPostProcessor.buildStructuredMaterial(block, year)
            // 票 07 提速：日期只到天——候选与 prompt 清单都截到 YYYY-MM-DD（去重）
            val dateOnly = material.normalizedDates.map { it.take(10) }.distinct()
            val prompt = PromptBuilder.buildFromOcr(categories, material.blockText, dateOnly, material.amounts)
            // 日期选择编码进文法：有候选=候选+空串；无候选=锁死空串（模型无法编造日期）
            val dateGrammar = GrammarGenerator.withDateAlternatives(grammar, dateOnly)
            val raw = transport.completeText(prompt, dateGrammar)
            // 金额锚定兜底（真机 51.6→20）：唯一的货币符号金额与模型输出冲突时确定性替换
            val draft = DraftNormalizer.parse(raw, categories)
            OcrPostProcessor.anchorAmount(draft, material)
        }
    }
}
