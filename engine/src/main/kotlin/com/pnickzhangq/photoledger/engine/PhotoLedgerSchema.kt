package com.pnickzhangq.photoledger.engine

/**
 * 提取 JSON Schema（Draft 字段契约，spec「提取字段契约」）：
 * 商家、日期（含口径标注）、实付款金额、币种、订单状态文本、类别建议。
 * 明细行不在 v1 契约内。
 */
data class ExtractionSchema(
    val categories: List<String>,
) {
    init {
        require(categories.isNotEmpty()) { "categories 不能为空：模型需从类别列表中选择" }
    }

    /** 类别枚举的 JSON Schema 片段（注入 prompt 与 grammar 共用，保证两处一致）。 */
    val categoryEnum: List<String> = categories

    fun toJsonSchema(): String = buildString {
        append("{")
        append("\"type\":\"object\",")
        append("\"properties\":{")
        append("\"merchant\":{\"type\":\"string\"},")
        append("\"amountPaid\":{\"type\":\"number\"},")
        append("\"currency\":{\"type\":\"string\",\"enum\":[\"CNY\",\"USD\",\"EUR\",\"JPY\",\"GBP\",\"HKD\",\"TWD\",\"KRW\",\"OTHER\"]},")
        append("\"datePaid\":{\"type\":\"string\"},")
        append("\"dateSource\":{\"type\":\"string\",\"enum\":[\"payment_time\",\"order_time\"]},")
        append("\"orderStatus\":{\"type\":\"string\"},")
        append("\"category\":{\"type\":\"string\",\"enum\":")
        append(categoryEnum.joinToString(",", "[", "]") { "\"$it\"" })
        append("}")
        append("},")
        append("\"required\":[\"merchant\",\"amountPaid\",\"currency\",\"datePaid\",\"dateSource\",\"orderStatus\",\"category\"],")
        append("\"additionalProperties\":false")
        append("}")
    }
}
