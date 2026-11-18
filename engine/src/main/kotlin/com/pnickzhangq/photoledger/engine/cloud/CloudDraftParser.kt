package com.pnickzhangq.photoledger.engine.cloud

import com.pnickzhangq.photoledger.engine.Draft
import com.pnickzhangq.photoledger.engine.FALLBACK_CATEGORY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 云端返回无法转成 Draft 时抛出（消息面向用户上屏）。 */
class CloudParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 票 39：云端模型原始输出 → Draft 列表（纯函数，JVM 可测）。
 *
 * 容错优先：不同家的 OpenAI 兼容 vision 接口在 JSON 形态上并不统一——
 * markdown 围栏、前后解释、裸数组、单订单对象、amount 是字符串都见过。
 * 但金额范围、日期格式、类别有效性仍按端侧同口径硬校验：错数据比缺数据伤害大。
 */
object CloudDraftParser {

    private const val MAX_AMOUNT = 1_000_000.0
    private const val MAX_MERCHANT = 40
    private const val MAX_CONTENT_ITEMS = 10
    private const val MAX_CONTENT_ITEM_CHARS = 30

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val numberRegex = Regex("""-?\d+(?:\.\d+)?""")
    private val dateRegex = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?""")

    fun parse(raw: String, categories: List<String>): List<Draft> {
        val root = try {
            json.parseToJsonElement(extractJson(raw))
        } catch (e: Exception) {
            throw CloudParseException("云端返回不是合法 JSON", e)
        }
        val orders = orderNodes(root)
        if (orders.isEmpty()) throw CloudParseException("云端未识别到订单")
        val drafts = orders.mapNotNull { (it as? JsonObject)?.let { o -> toDraft(o, categories) } }
        if (drafts.isEmpty()) throw CloudParseException("云端返回的订单缺少有效金额")
        return drafts
    }

    /** orders 数组 / 单订单对象 / 裸数组三种形态。 */
    private fun orderNodes(root: JsonElement): List<JsonElement> = when (root) {
        is JsonArray -> root.toList()
        is JsonObject -> when {
            root["orders"] is JsonArray -> (root["orders"] as JsonArray).toList()
            root.containsKey("amountPaid") -> listOf(root)
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun toDraft(o: JsonObject, categories: List<String>): Draft? {
        // 金额非法 = 这一单不可信，直接丢弃；全部丢弃时由 parse 统一报错
        val amount = parseAmount(o["amountPaid"]) ?: return null
        if (amount < 0.0 || amount >= MAX_AMOUNT) return null

        val rawCategory = string(o["category"]).trim()
        val categoryKnown = rawCategory in categories
        val category = when {
            categoryKnown -> rawCategory
            FALLBACK_CATEGORY in categories -> FALLBACK_CATEGORY
            else -> categories.firstOrNull().orEmpty()
        }

        return Draft(
            merchant = string(o["merchant"]).trim()
                .removeSurrounding("\"")
                .replace(Regex("\\s+"), " ")
                .take(MAX_MERCHANT),
            amountPaid = amount,
            datePaid = parseDate(string(o["datePaid"])),
            category = category,
            // 类别漂移落「其他」时不静默：确认页提示用户重点核对
            needsReview = !categoryKnown,
            content = parseContent(o["content"]),
        )
    }

    private fun parseAmount(e: JsonElement?): Double? {
        val text = when (e) {
            is JsonPrimitive -> e.content
            is JsonObject -> string(e["value"]).ifBlank { string(e["amount"]) }
            else -> return null
        }
        val cleaned = text.replace(",", "").replace("，", "")
        return numberRegex.find(cleaned)?.value?.toDoubleOrNull()
    }

    /** 只到天（与 Draft 契约一致）；无法识别或越界留空串（合法）。 */
    private fun parseDate(text: String): String {
        val m = dateRegex.find(text) ?: return ""
        val (year, monthText, dayText) = m.destructured
        val month = monthText.toIntOrNull() ?: return ""
        val day = dayText.toIntOrNull() ?: return ""
        if (month !in 1..12 || day !in 1..31) return ""
        return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    /** 数组/对象/字符串三种商品清单形态 → 「名称×数量、名称」；去重、限长。 */
    private fun parseContent(e: JsonElement?): String {
        val items = when (e) {
            null, is JsonNull -> emptyList()
            is JsonArray -> e.flatMap { item ->
                when (item) {
                    is JsonPrimitive -> splitItems(item.content)
                    is JsonObject -> {
                        val name = string(item["name"])
                            .ifBlank { string(item["title"]) }
                            .ifBlank { string(item["text"]) }
                            .ifBlank { string(item["item"]) }
                        val qty = parseQty(item["quantity"]) ?: parseQty(item["qty"]) ?: parseQty(item["count"])
                        if (name.isBlank()) emptyList()
                        else listOf(if (qty != null && qty > 1) "${name.trim()}×$qty" else name.trim())
                    }
                    else -> emptyList()
                }
            }
            is JsonPrimitive -> splitItems(e.content)
            else -> emptyList()
        }
        return items
            .map { it.trim().trimStart('-', '•', '·', '*').trim().removeSurrounding("\"").take(MAX_CONTENT_ITEM_CHARS) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_CONTENT_ITEMS)
            .joinToString("、")
    }

    private fun splitItems(text: String): List<String> =
        text.split(Regex("""[、,，;；\n]"""))

    private fun parseQty(e: JsonElement?): Int? {
        val text = when (e) {
            is JsonPrimitive -> e.content
            is JsonObject -> string(e["value"])
            else -> return null
        }
        if (text.isBlank()) return null
        val n = numberRegex.find(text)?.value?.toDoubleOrNull() ?: return null
        return n.toInt().takeIf { it > 0 }
    }

    private fun string(e: JsonElement?): String = when (e) {
        null, is JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> ""
    }

    /** 剥 markdown 围栏与前后解释，取第一段完整 JSON（对象优先，其次数组）。 */
    private fun extractJson(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```")
            if (t.startsWith("json", ignoreCase = true)) t = t.substring(4)
            val fenceEnd = t.lastIndexOf("```")
            if (fenceEnd >= 0) t = t.substring(0, fenceEnd)
            t = t.trim()
        }
        val objStart = t.indexOf('{')
        val objEnd = t.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) return t.substring(objStart, objEnd + 1)
        val arrStart = t.indexOf('[')
        val arrEnd = t.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) return t.substring(arrStart, arrEnd + 1)
        return t
    }
}
