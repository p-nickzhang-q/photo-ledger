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
        // 对象骨架：字段按固定顺序输出，全部必填（契约要求 7 字段齐全）
        append("root ::= \"{\" ws \"\\\"merchant\\\"\" ws \":\" ws merchant \",\" ws")
        append(" \"\\\"amountPaid\\\"\" ws \":\" ws amount \",\" ws")
        append(" \"\\\"currency\\\"\" ws \":\" ws currency \",\" ws")
        append(" \"\\\"datePaid\\\"\" ws \":\" ws date \",\" ws")
        append(" \"\\\"dateSource\\\"\" ws \":\" ws dateSource \",\" ws")
        append(" \"\\\"orderStatus\\\"\" ws \":\" ws string \",\" ws")
        append(" \"\\\"category\\\"\" ws \":\" ws category \"}\"")
        append("\n")

        // 自由文本（商家名、订单状态）：CJK + ASCII 可打印，禁止裸引号与裸反斜杠
        append("string ::= \"\\\"\" ( [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\\\"\" | \"\\\\\\\\\" | \"\\\\n\" | \"\\\\t\" )* \"\\\"\"\n")
        append("merchant ::= string\n")

        // 金额：非负数字，最多两位小数
        append("amount ::= [0-9]+ (\".\" [0-9] [0-9]?)?\n")

        // 币种与类别：枚举字面量
        append("currency ::=")
        CURRENCIES.joinTo(this, " | ") { "\"\\\"$it\\\"\"" }
        append("\n")

        append("category ::=")
        schema.categoryEnum.joinTo(this, " | ") { "\"\\\"$it\\\"\"" }
        append("\n")

        // 日期：YYYY-MM-DD[ HH:MM:SS]，月/日/时/分/秒带范围约束（0 开头或 1-9 开头组合）
        append("date ::= \"\\\"\" year \"-\" month \"-\" day (\" \" hour \":\" minute \":\" second)? \"\\\"\"\n")
        append("year ::= [0-9] [0-9] [0-9] [0-9]\n")
        append("month ::= \"0\" [1-9] | \"1\" [0-2]\n")
        append("day ::= \"0\" [1-9] | [1-2] [0-9] | \"3\" [0-1]\n")
        append("hour ::= [01] [0-9] | \"2\" [0-3]\n")
        append("minute ::= [0-5] [0-9]\n")
        append("second ::= [0-5] [0-9]\n")

        // 口径枚举
        append("dateSource ::= \"\\\"payment_time\\\"\" | \"\\\"order_time\\\"\"\n")

        // ws：GBNF 标准空白
        append("ws ::= [ \\t\\n]*\n")
    }.trimEnd()

    private val CURRENCIES = listOf("CNY", "USD", "EUR", "JPY", "GBP", "HKD", "TWD", "KRW", "OTHER")

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
}
