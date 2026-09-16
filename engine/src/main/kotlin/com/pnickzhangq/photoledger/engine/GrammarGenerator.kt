package com.pnickzhangq.photoledger.engine

/**
 * 由 [ExtractionSchema] 生成 GBNF 文法（llama.cpp 约束解码用）。
 *
 * 设计取舍：schema 字段固定为 Draft 契约的 7 个字段，因此不做通用 JSON Schema → GBNF
 * 的全量转换（那是 llama.cpp 仓库 json_schema_to_grammar.py 的职责），只生成与
 * [PhotoLedgerSchema] 契约一一对应的文法，保证 prompt 与 grammar 两处一致且可测。
 */
object GrammarGenerator {

    fun fromSchema(schema: ExtractionSchema): String = buildString {
        // 对象骨架：字段按固定顺序输出，全部必填（票 07 提速：只约束用户需要的 4 字段）
        append("root ::= \"{\" ws \"\\\"merchant\\\"\" ws \":\" ws merchant \",\" ws")
        append(" \"\\\"amountPaid\\\"\" ws \":\" ws amount \",\" ws")
        append(" \"\\\"datePaid\\\"\" ws \":\" ws date \",\" ws")
        append(" \"\\\"category\\\"\" ws \":\" ws category \"}\"")
        append("\n")

        // 自由文本（商家名）：CJK + ASCII 可打印，禁止裸引号与裸反斜杠
        append("string ::= \"\\\"\" ( [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\\\"\" | \"\\\\\\\\\" | \"\\\\n\" | \"\\\\t\" )* \"\\\"\"\n")
        append("merchant ::= string\n")

        // 金额：非负数字，最多两位小数
        append("amount ::= [0-9]+ (\".\" [0-9] [0-9]?)?\n")

        // 类别：枚举字面量
        append("category ::=")
        schema.categoryEnum.joinTo(this, " | ") { "\"\\\"$it\\\"\"" }
        append("\n")

        // 日期：YYYY-MM-DD（票 07 提速：只到天，时/分/秒不再让模型生成）
        append("date ::= \"\\\"\" year \"-\" month \"-\" day \"\\\"\"\n")
        append("year ::= [0-9] [0-9] [0-9] [0-9]\n")
        append("month ::= \"0\" [1-9] | \"1\" [0-2]\n")
        append("day ::= \"0\" [1-9] | [1-2] [0-9] | \"3\" [0-1]\n")

        // ws：GBNF 标准空白
        append("ws ::= [ \\t\\n]*\n")
    }.trimEnd()

    /**
     * OCR 路线专用：把 date 规则替换为「候选清单字面量二选一」（清单为空或不含当日候选时
     * 允许空字符串兜底）。把生成任务降为选择任务——小模型拼日期不可靠（0.6B 实测），
     * 从清单里选则不可能出畸形值。
     */
    fun withDateAlternatives(grammar: String, candidates: List<String>): String {
        require(candidates.isNotEmpty()) { "候选清单为空时应使用原 grammar" }
        val dateRule = grammar.lineSequence().firstOrNull { it.startsWith("date ::= ") }
            ?: return grammar
        val alternatives = candidates.joinToString(" | ") { "\"\\\"$it\\\"\"" }
        val newRule = "date ::= $alternatives | \"\\\"\\\"\""
        return grammar.lines().joinToString("\n") { if (it == dateRule) newRule else it }
    }

    /**
     * GBNF → JSON Schema 转译（LiteRT-LM ResponseFormat/LLGuidance 约束用，票 13/14）。
     * 本仓 Draft 契约专用，非通用转换器：
     * 1. date 规则是 [withDateAlternatives] 注入的候选清单（行内含 `|`）时，转 datePaid enum，
     *    并保留清单自带的空串兜底选项（prompt 口径：无付款时间时 datePaid 填空串）；
     * 2. date 规则是原始范围规则（行内无 `|`）时，datePaid 必须为自由 string——
     *    范围规则文本内含裸引号字符，误当候选解析会产出非法 schema
     *    （票 07 真机回归：无日期截图触发 Gson "Unterminated array"，该图提取固定失败）；
     * 3. category 枚举从规则行提取；契约 4 字段（merchant/amountPaid/datePaid/category）。
     */
    fun toJsonSchema(grammar: String): String {
        val dateLine = grammar.lineSequence().firstOrNull { it.startsWith("date ::=") }
        val dateCandidates = dateLine
            ?.takeIf { it.contains('|') }
            ?.removePrefix("date ::=")
            ?.split("|")
            ?.map(::stripGbnfQuotes)
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?: emptyList()

        fun enumFrom(ruleName: String): List<String> {
            val line = grammar.lineSequence().firstOrNull { it.startsWith("$ruleName ::=") } ?: return emptyList()
            return line.removePrefix("$ruleName ::=").split("|").map(::stripGbnfQuotes).filter { it.isNotEmpty() }
        }

        val categories = enumFrom("category")

        fun enumClause(name: String, values: List<String>, fallbackType: String = "string"): String =
            if (values.isEmpty()) {
                "\"$name\":{\"type\":\"$fallbackType\"}"
            } else {
                val enumItems = values.joinToString(",") { v -> "\"" + v + "\"" }
                "\"$name\":{\"type\":\"string\",\"enum\":[$enumItems]}"
            }

        // datePaid：有候选时含空串选项（对齐 GBNF 的 "" 兜底）；无候选时自由 string
        val datePaidValues = if (dateCandidates.isEmpty()) emptyList() else listOf("") + dateCandidates

        return buildString {
            append("{")
            append("\"type\":\"object\",")
            append("\"properties\":{")
            append("\"merchant\":{\"type\":\"string\"},")
            append("\"amountPaid\":{\"type\":\"number\"},")
            append(enumClause("datePaid", datePaidValues)).append(",")
            append(enumClause("category", categories))
            append("},")
            append("\"required\":[\"merchant\",\"amountPaid\",\"datePaid\",\"category\"],")
            append("\"additionalProperties\":false")
            append("}")
        }
    }

    /**
     * 剥 GBNF 字符串字面量的引号，迭代至裸值。备选字面量是双层形态 `"\"CNY\""`
     * （普通引号包裹 GBNF 转义引号），只剥一层会把 `"CNY"`（带引号）当枚举值——
     * 票 13 的「LLGuidance enum 双重编码输出」quirk 即源于此，schema 修对后出口清洗只是兜底。
     */
    private fun stripGbnfQuotes(token: String): String {
        var t = token.trim()
        var changed = true
        while (changed && t.length >= 2) {
            changed = false
            if (t.startsWith("\"") && t.endsWith("\"")) {
                t = t.substring(1, t.length - 1); changed = true
            } else if (t.startsWith("\\\"") && t.endsWith("\\\"")) {
                t = t.substring(2, t.length - 2); changed = true
            }
        }
        return t
    }
}
