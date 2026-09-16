package com.pnickzhangq.photoledger.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型原始输出 → [Draft] 的解析与口径归一。
 *
 * 职责边界：JSON 结构合法性由约束解码保证（GBNF/LiteRT Schema），
 * 这里做的是字段级校验与契约归一（类别须在用户列表内、金额非负）。
 * 票 07 提速：模型只产出 4 个用户字段；currency/dateSource/orderStatus 在此填默认值。
 */
object DraftNormalizer {

    // 票 13：JSON Schema 约束下模型可能输出 $schema 等元字段（LiteRT/LLGuidance 行为），
    // 契约字段照常校验，未知键容忍——required 四字段仍然强制。
    private val json = Json { ignoreUnknownKeys = true }

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
        val datePaid = root["datePaid"]!!.jsonPrimitive.content
        val category = root["category"]!!.jsonPrimitive.content
        if (category !in categories) {
            throw ExtractionParseException("类别不在用户列表中：$category")
        }

        return Draft(
            merchant = merchant,
            amountPaid = amount,
            datePaid = datePaid,
            category = category,
        )
    }

    private val REQUIRED_FIELDS = listOf("merchant", "amountPaid", "datePaid", "category")
}
