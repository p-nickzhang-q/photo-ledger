package com.pnickzhangq.photoledger.engine.cloud

/**
 * 票 39：云端视觉识别 prompt。
 *
 * 与端侧 PromptBuilder 分开：端侧受 30 张质量闸门与 GBNF 契约保护，本次不碰；
 * 云端模型能力更强，一次请求直接产出全部字段（含端侧已放弃的 merchant 与新增 content）。
 * 口径反例（实付款 vs 商品总价、付款时间 vs 下单时间）沿用 CONTEXT.md 的硬要求。
 */
object CloudPromptBuilder {

    fun build(categories: List<String>): String = buildString {
        appendLine("你是记账助手。看这张支付订单截图，提取图中所有订单。")
        appendLine("只输出 JSON，不要任何解释或 markdown 标记，格式：")
        appendLine(
            """{"orders":[{"merchant":"","content":["商品名×数量"],"amountPaid":0,"datePaid":"YYYY-MM-DD","category":""}]}""",
        )
        appendLine()
        appendLine("字段规则：")
        appendLine("- merchant：商户/店铺名，逐字照抄截图；不确定就留空字符串")
        appendLine("- content：购买的商品或服务名称列表，每项写成「名称×数量」，不要写价格；只写截图上出现的商品；不确定就留空数组")
        appendLine("- amountPaid：实付款金额，即用户实际支付的钱。取「实付款」「实付」「付款金额」，不要取商品总价、运费或优惠前价格")
        appendLine("- datePaid：付款发生的日期，格式 YYYY-MM-DD。优先取付款时间；没有付款时间时取下单时间；都没有就留空字符串")
        appendLine("- category：必须从以下类别中选最贴切的一个：${categories.joinToString("、")}")
        appendLine()
        appendLine("一张截图可能有多个订单，每个订单一个元素；没有识别到订单时 orders 为空数组。")
        appendLine("不要编造截图上没有的信息。")
    }
}
