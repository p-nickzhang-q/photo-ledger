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
        /**
         * 票 27/30：商户记忆解析钩子——逐块调用，入参为该订单区块**范围内**的行
         * （引擎按区块 y 范围从原始行取回、剔除页面 UI 条带、保留低分徽标行——
         * 票 32），不是整图，多单时各块各自匹配，防同图多单回填成同一商户。
         * 命中返回别名对象（canonical+category），引擎负责类别有效性校验。null = 不启用。
         */
        merchantResolver: (suspend (blockLines: List<OcrLine>) -> MerchantAlias?)? = null,
        /**
         * 票 29：规则快路径开关——逐块先试确定性提取（唯一实付强锚 + 唯一日期 +
         * 品牌类别命中 → 毫秒级出草稿），不满足的块降级 LLM。App/闸门开 true
         * （闸门验证的就是真实生产行为），默认 false 保持旧调用方语义。
         */
        fastPath: Boolean = false,
    ): List<Draft> {
        val usable = lines.filter { it.score >= OcrPostProcessor.MIN_LINE_SCORE }
        if (usable.isEmpty()) return emptyList()
        // 票 32：页面 UI 条带（tab 栏/筛选 chips/动作图标行）整体出局——真机事故：
        // 「飞猪旅行」标签让首单错配商户「飞猪」；同时防条带进 prompt 干扰类别。
        val uiStrips = OcrPostProcessor.uiStripLines(lines)
        // 票 23 提速：逐行丢弃噪音行（详情页的 UI 动作/运营位行不进
        // blockText 与金额/日期候选，prompt token 与假金额参考同步减少）
        val trimmed = OcrPostProcessor.trimNoiseTail(usable.filter { it !in uiStrips })
        if (trimmed.isEmpty()) return emptyList()
        val blocks = OcrPostProcessor.splitOrderBlocks(trimmed)
        val year = OcrPostProcessor.guessContextYear(trimmed) ?: fallbackYear
        val validCategories = categories.toSet()
        return blocks.map { block ->
            // 票 32：商户匹配用「区块 y 范围内的原始行」剔除条带（含被置信度滤掉
            // 的徽标行，如「闪购YY」）；逐块取范围保持多单各配各的行（票 30）
            val matchLines = OcrPostProcessor.merchantMatchLines(lines, block, uiStrips)
            val scopedResolver: (suspend (List<OcrLine>) -> MerchantAlias?)? =
                merchantResolver?.let { r -> { _: List<OcrLine> -> r(matchLines) } }
            // 票 29：规则快路径先行，拿不准的块降级 LLM
            if (fastPath) {
                val fast = try {
                    RulesFastPath.tryBuild(block, year, scopedResolver)
                } catch (t: Throwable) {
                    null
                }
                if (fast != null) return@map fast
            }
            val material = OcrPostProcessor.buildStructuredMaterial(block, year)
            // 票 07 提速：日期只到天——候选与 prompt 清单都截到 YYYY-MM-DD（去重）
            val dateOnly = material.normalizedDates.map { it.take(10) }.distinct()
            // 票 28-A：金额候选按打分降序喂给模型，缩小数字噪声下的搜索空间
            val amountOrder = material.amountCandidates.map { it.value }
            val prompt = PromptBuilder.buildFromOcr(
                categories, material.blockText, dateOnly, amountOrder, amountOrder.firstOrNull(),
            )
            // 日期选择编码进文法：有候选=候选+空串；无候选=锁死空串（模型无法编造日期）
            val dateGrammar = GrammarGenerator.withDateAlternatives(grammar, dateOnly)
            val raw = transport.completeText(prompt, dateGrammar)
            // 金额锚定兜底（真机 51.6→20）：唯一的货币符号金额与模型输出冲突时确定性替换
            val draft = DraftNormalizer.parse(raw, categories)
            val anchored = OcrPostProcessor.anchorAmount(draft, material)
            // 票 28-B：交叉验证失败 → 低置信标志，确认页提示重点核对
            var final = anchored.copy(needsReview = OcrPostProcessor.needsReview(anchored, material, dateOnly))
            // 票 27/30：商户记忆回填（逐块）+ 类别联动（类别须在当前类别列表内）
            if (final.merchant.isBlank() && scopedResolver != null) {
                val hit = try {
                    scopedResolver(block)
                } catch (t: Throwable) {
                    null
                }
                if (hit != null) {
                    val category = hit.category?.takeIf { it in validCategories }
                    final = final.copy(merchant = hit.canonical, category = category ?: final.category)
                }
            }
            final
        }
    }
}
