package com.pnickzhangq.photoledger.engine

import kotlinx.serialization.Serializable

/** 日期口径来源（CONTEXT.md「付款时间」：付款时间优先，退下单时间）。 */
enum class DateSource {
    /** 截图上明示的付款时间（首选口径）。 */
    PAYMENT_TIME,

    /** 截图上缺失付款时间，退用下单时间。 */
    ORDER_TIME,
}

/**
 * 提取结果（CONTEXT.md「Draft」：待确认状态，确认后才成为 Entry）。
 * 金额口径 = 实付款（CONTEXT.md「实付款」：商品总价、运费、优惠不参与统计）。
 *
 * 票 07 提速：模型只输出 amountPaid/datePaid/category 等用户需要的字段
 * （decode token 减半）；currency/dateSource/orderStatus 由引擎填默认值，不再要求模型生成。
 * 2026-09 契约再收缩：merchant 退出模型输出（0.6B 抄写中文店名太弱，真机连续出错：
 * prompt 示例泄漏/列表截断碎片/抄错行）——留空由用户详情页后补，与「错数据比缺数据伤害大」口径一致。
 */
@Serializable
data class Draft(
    /** 商家名：模型不再输出，默认空串；详情页可编辑补填。 */
    val merchant: String = "",
    /** 实付款金额（非商品总价）。 */
    val amountPaid: Double,
    /** 币种，默认 CNY（模型不再输出，人工/遗留路径可覆盖）。 */
    val currency: String = "CNY",
    /** 日期，取付款时间优先退下单时间，只到天（YYYY-MM-DD）；无日期为空串。 */
    val datePaid: String,
    /** 日期口径：模型不再输出，默认按付款时间。 */
    val dateSource: DateSource = DateSource.PAYMENT_TIME,
    /** 订单状态文本（模型不再输出，默认空；手工/编辑界面可填）。 */
    val orderStatus: String = "",
    /** 类别建议（从用户类别列表中选）。 */
    val category: String,
)

/** 模型输出无法解析为合法 Draft 时抛出。 */
class ExtractionParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
