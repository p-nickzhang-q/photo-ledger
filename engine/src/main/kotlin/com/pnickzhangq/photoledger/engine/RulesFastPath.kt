package com.pnickzhangq.photoledger.engine

/**
 * 规则快路径（票 29）：确定性提取先行，LLM 降级兜底。
 *
 * 背景：票 27/28 之后核心字段几乎全由确定性逻辑承责（金额候选打分/锚定强改、
 * 日期文法锁死、商户本地匹配），30 张闸门里模型输出与规则候选几乎重合，而
 * 0.6B 每单 8s+ 解码且会抄错（100/100元 案例）。本路径对「规则能拿准」的区块
 * 毫秒级直接出 Draft，拿不准的降级 LLM——App 大部分导入预期变成秒出。
 *
 * 单区块快路径条件（全部满足才出草稿，任一不满足返回 null 降级）：
 * 1. 金额：候选非空，且（top1 为实付强锚 score ≥ [STRONG_ANCHOR]）或（唯一候选）；
 *   多候选时 top1-top2 分差 ≥ [CLEAR_MARGIN]（防「付款金额15.5/实付款28」
 *   双标签页选错）
 * 2. 日期：锚定候选 ≤1 个（唯一取值；0 个为合法空——导入时兜底截图日期）
 * 3. 类别：品牌字典（DEFAULT_MERCHANT_MEMORY，种子同源）命中——无命中降级
 *   LLM（保持类别质量，不输出「其他」拉低口径）
 * 商户：复用 merchantResolver 逐块钩子（票 27/30），未命中留空。
 * needsReview：金额/日期按构造必然通过交叉验证，恒 false。
 */
object RulesFastPath {

    /** 实付强锚分值：货币符号(+40) + 实付类关键词(+30)。 */
    private const val STRONG_ANCHOR = 70

    /** 多候选时 top1-top2 的最小分差（低于此视为打架，降级）。 */
    private const val CLEAR_MARGIN = 20

    /** 品牌字典 → 类别（种子同源，一次构建）。 */
    private val categoryAliases: List<MerchantAlias> =
        DEFAULT_MERCHANT_MEMORY.map { MerchantAlias(alias = it.key, canonical = it.key, category = it.value) }

    /**
     * 尝试纯规则出草稿；不满足条件返回 null（调用方降级 LLM）。
     * [merchantResolver] 与 LLM 路径同款逐块钩子，可为 null（商户留空）。
     */
    suspend fun tryBuild(
        block: List<OcrLine>,
        contextYear: Int?,
        merchantResolver: (suspend (blockLines: List<OcrLine>) -> MerchantAlias?)?,
    ): Draft? {
        val material = OcrPostProcessor.buildStructuredMaterial(block, contextYear)
        val candidates = material.amountCandidates
        if (candidates.isEmpty()) return null
        val top = candidates.first()
        val strong = top.score >= STRONG_ANCHOR || candidates.size == 1
        val clear = candidates.size == 1 || top.score - candidates[1].score >= CLEAR_MARGIN
        if (!strong || !clear) return null

        val dateOnly = material.normalizedDates.map { it.take(10) }.distinct()
        if (dateOnly.size > 1) return null

        // 类别：品牌字典命中才走快路径（无命中降级 LLM，保持类别质量）
        val category = MerchantMatcher.matchDetail(
            block.map { it.text }, categoryAliases,
        )?.category ?: return null

        val merchant = merchantResolver?.invoke(block)?.canonical ?: ""
        return Draft(
            merchant = merchant,
            amountPaid = top.value,
            currency = "CNY",
            datePaid = dateOnly.singleOrNull() ?: "",
            dateSource = DateSource.PAYMENT_TIME,
            orderStatus = "",
            category = category,
            needsReview = false,
        )
    }
}
