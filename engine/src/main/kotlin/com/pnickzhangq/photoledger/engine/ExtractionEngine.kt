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
}
