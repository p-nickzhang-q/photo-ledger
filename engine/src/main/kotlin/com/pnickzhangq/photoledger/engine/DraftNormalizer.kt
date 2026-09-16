package com.pnickzhangq.photoledger.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型原始输出 → [Draft] 的解析与口径归一。
 *
 * 职责边界：JSON 结构合法性由 GBNF 约束解码保证（本票验收项），
 * 这里做的是字段级校验与契约归一（类别须在用户列表内、币种白名单、金额非负）。
 */
object DraftNormalizer {

    // 票 13：JSON Schema 约束下模型可能输出 $schema 等元字段（LiteRT/LLGuidance 行为），
    // 契约字段照常校验，未知键容忍——required 七字段仍然强制。
    private val json = Json { ignoreUnknownKeys = true }

    /** 币种白名单与 [GrammarGenerator] 的枚举保持一致。 */
    val CURRENCIES = listOf("CNY", "USD", "EUR", "JPY", "GBP", "HKD", "TWD", "KRW", "OTHER")

    fun parse(raw: String, categories: List<String>): Draft {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw ExtractionParseException("模型输出不是合法 JSON 对象：${raw.take(120)}", e)
        }

        val missing = REQUIRED_FIELDS.filter { it !in root.keys }
        if (missing.isNotEmpty()) {
            throw ExtractionParseException("缺少必需字段：$missing")
        }

        val merchant = root["merchant"]!!.jsonPrimitive.content
        val amount = root["amountPaid"]!!.jsonPrimitive.doubleOrNull
            ?: throw ExtractionParseException("amountPaid 不是数字：${root["amountPaid"]}")
        if (amount < 0) throw ExtractionParseException("amountPaid 为负：$amount")
        val currency = root["currency"]!!.jsonPrimitive.content
        if (currency !in CURRENCIES) throw ExtractionParseException("币种不在白名单：$currency")
        val datePaid = root["datePaid"]!!.jsonPrimitive.content
        val dateSource = when (val ds = root["dateSource"]!!.jsonPrimitive.content) {
            "payment_time" -> DateSource.PAYMENT_TIME
            "order_time" -> DateSource.ORDER_TIME
            else -> throw ExtractionParseException("dateSource 非法：$ds")
        }
        val orderStatus = root["orderStatus"]!!.jsonPrimitive.content
        val category = root["category"]!!.jsonPrimitive.content
        if (category !in categories) {
            throw ExtractionParseException("类别不在用户列表中：$category")
        }

        return Draft(
            merchant = merchant,
            amountPaid = amount,
            currency = currency,
            datePaid = datePaid,
            dateSource = dateSource,
            orderStatus = orderStatus,
            category = category,
        )
    }

    private val REQUIRED_FIELDS = listOf(
        "merchant", "amountPaid", "currency", "datePaid", "dateSource", "orderStatus", "category",
    )
}
