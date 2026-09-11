package com.pnickzhangq.photoledger.engine

/**
 * 质量闸门评分（票 02，spec「质量闸门」）：
 * 硬字段（实付款金额、日期）≥95% 完全正确；软字段（商家、类别）≥80% 且错误可一眼修正。
 * 字段内容对错在这里量化；结构合法性由 GBNF 保证（票 01/12 已验收）。
 */
object GateScoring {

    /** 闸门标准（ADR-0001 数字）。 */
    const val HARD_THRESHOLD = 0.95
    const val SOFT_THRESHOLD = 0.80

    /** 单图评分：标注（ground truth）vs 提取 Draft。 */
    data class ImageScore(
        val file: String,
        val draftCount: Int,
        /** 硬字段：该图是否命中标注（多单图取金额/日期都能对上的那条；拆多单策略见 findMatch）。 */
        val amountCorrect: Boolean?,
        val dateCorrect: Boolean?,
        val merchantCorrect: Boolean?,
        val categoryCorrect: Boolean?,
        /** null 表示无匹配 Draft（提取失败/多单错拆），计为全错。 */
        val hasMatch: Boolean,
    )

    /** 日期比较取「日」粒度：标注只到日期（无时间），提取带时分秒，等价于同一天即正确。 */
    fun dateMatches(annotation: String, draft: String): Boolean =
        annotation.trim().take(10) == draft.trim().take(10)

    /** 金额比较：0.005 容差（浮点/两位小数四舍五入）。 */
    fun amountMatches(annotation: Double, draft: Double): Boolean =
        kotlin.math.abs(annotation - draft) < 0.005

    /**
     * 多单图匹配：标注是一笔账，提取可能拆出多条。取「金额与日期都正确」的条目判定命中；
     * 若无全对条目，取金额或日期任一正确的最接近条目用于逐字段归因。
     */
    fun scoreImage(
        file: String,
        annotationAmount: Double,
        annotationDate: String,
        annotationMerchant: String?,
        annotationCategory: String?,
        drafts: List<Draft>,
    ): ImageScore {
        val fullMatch = drafts.firstOrNull {
            amountMatches(annotationAmount, it.amountPaid) && dateMatches(annotationDate, it.datePaid)
        }
        if (fullMatch != null) {
            return ImageScore(
                file = file,
                draftCount = drafts.size,
                amountCorrect = true,
                dateCorrect = true,
                merchantCorrect = annotationMerchant?.let { it == fullMatch.merchant } ?: (true),
                categoryCorrect = annotationCategory?.let { it == fullMatch.category } ?: true,
                hasMatch = true,
            )
        }
        // 无全对：挑「金额正确」优先、其次「日期正确」的条目做字段级归因
        val nearest = drafts.minByOrNull { draft ->
            val amountDiff = kotlin.math.abs(annotationAmount - draft.amountPaid)
            val dateDiff = if (dateMatches(annotationDate, draft.datePaid)) 0.0 else 1.0
            amountDiff + dateDiff
        }
        return ImageScore(
            file = file,
            draftCount = drafts.size,
            amountCorrect = nearest?.let { amountMatches(annotationAmount, it.amountPaid) } ?: false,
            dateCorrect = nearest?.let { dateMatches(annotationDate, it.datePaid) } ?: false,
            merchantCorrect = nearest?.let { annotationMerchant?.let { m -> m == it.merchant } ?: true } ?: false,
            categoryCorrect = nearest?.let { annotationCategory?.let { c -> c == it.category } ?: true } ?: false,
            hasMatch = false,
        )
    }

    /** 汇总与闸门结论。 */
    data class Summary(
        val totalImages: Int,
        val totalDrafts: Int,
        val amountAccuracy: Double,
        val dateAccuracy: Double,
        val merchantAccuracy: Double,
        val categoryAccuracy: Double,
        val pass: Boolean,
        val failures: List<ImageScore>,
    )

    fun summarize(scores: List<ImageScore>): Summary {
        val n = scores.size.toDouble()
        val amountAcc = scores.count { it.amountCorrect == true } / n
        val dateAcc = scores.count { it.dateCorrect == true } / n
        val merchantAcc = scores.count { it.merchantCorrect == true } / n
        val categoryAcc = scores.count { it.categoryCorrect == true } / n
        val hardOk = amountAcc >= HARD_THRESHOLD && dateAcc >= HARD_THRESHOLD
        val softOk = merchantAcc >= SOFT_THRESHOLD && categoryAcc >= SOFT_THRESHOLD
        return Summary(
            totalImages = scores.size,
            totalDrafts = scores.sumOf { it.draftCount },
            amountAccuracy = amountAcc,
            dateAccuracy = dateAcc,
            merchantAccuracy = merchantAcc,
            categoryAccuracy = categoryAcc,
            pass = hardOk && softOk,
            failures = scores.filter { it.amountCorrect != true || it.dateCorrect != true },
        )
    }
}
