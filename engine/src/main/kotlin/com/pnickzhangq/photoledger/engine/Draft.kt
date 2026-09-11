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
 */
@Serializable
data class Draft(
    val merchant: String,
    /** 实付款金额（非商品总价）。 */
    val amountPaid: Double,
    /** 币种，默认 CNY（CONTEXT.md「币种」）。 */
    val currency: String,
    /** 日期，取付款时间优先退下单时间。 */
    val datePaid: String,
    /** 日期口径标注：付款时间 / 下单时间。 */
    val dateSource: DateSource,
    /** 订单状态文本（v1 仅展示，不参与账目逻辑）。 */
    val orderStatus: String,
    /** 类别建议（从用户类别列表中选）。 */
    val category: String,
)

/** 模型输出无法解析为合法 Draft 时抛出。 */
class ExtractionParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
