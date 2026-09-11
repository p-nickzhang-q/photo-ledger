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
}
