package com.pnickzhangq.photoledger.engine

/**
 * 提取 prompt 构造。
 *
 * 口径要求来自 CONTEXT.md 词汇表：
 * - 金额取「实付款」（用户实际支付的钱），不取商品总价/运费/优惠明细
 * - 日期取「付款时间」，截图上没有时退「下单时间」，并在 dateSource 里标注口径
 * - 订单状态是截图原文本，v1 只展示不参与逻辑
 */
object PromptBuilder {

    fun build(categories: List<String>): String = buildString {
        appendLine("你是记账助手。从这张订单截图中提取以下字段，以 JSON 输出。")
        appendLine()
        appendLine("字段说明：")
        appendLine("- merchant: 商家或平台名称（如「美团」「淘宝」「京东」或具体店铺名）")
        appendLine("- amountPaid: 实付款金额，即用户为这笔订单实际支付的钱。取「实付款」「实付」「付款金额」，不要取商品总价、运费或优惠前价格")
        appendLine("- currency: 币种代码，人民币为 CNY")
        appendLine("- datePaid: 支付发生的时间。优先取付款时间；截图上没有付款时间时取下单时间")
        appendLine("- dateSource: datePaid 的口径标注。取了付款时间填 payment_time，取了下单时间填 order_time")
        appendLine("- orderStatus: 订单状态原文（如「已完成」「待付款」「已退款」），保持截图上的原文本")
        appendLine("- category: 从以下类别中选最贴切的一个：${categories.joinToString("、")}")
        appendLine()
        appendLine("只输出 JSON，不要任何解释或 markdown 标记。")
    }

    /**
     * OCR 文本行版（票 12）：输入是后处理层整理过的素材而非原始截图。
     * 口径要求与图像版完全一致；额外给出规范化日期/金额清单作锚点。
     * 票 10 速度优化：字段说明压缩到单行——端侧 prefill 是耗时大头
     * （410 token 中说明占 ~250），质量闸门复验通过后保留压缩版。
     */
    fun buildFromOcr(
        categories: List<String>,
        ocrText: String,
        normalizedDates: List<String>,
        amounts: List<Double>,
    ): String = buildString {
        appendLine("订单截图的 OCR 文本行（可能有错字或无关内容）：")
        appendLine(ocrText)
        if (normalizedDates.isNotEmpty()) {
            appendLine("规范化日期（datePaid 逐字取自清单，不要自拼）：${normalizedDates.joinToString("、")}")
        } else {
            appendLine("无可识别日期。datePaid 用空字符串。")
        }
        if (amounts.isNotEmpty()) {
            appendLine("金额数字（参考）：${amounts.joinToString("、")}")
        }
        appendLine("提取为 JSON：merchant 取店铺名而非平台名（如「闪购」「淘宝」是反例）；amountPaid 取「实付款/实付」后的数字；currency 人民币为 CNY；datePaid 优先付款时间否则下单时间；dateSource 填 payment_time 或 order_time；orderStatus 取状态原文；category 从中选择：${categories.joinToString("、")}")
        appendLine("只输出 JSON。")
    }
}
