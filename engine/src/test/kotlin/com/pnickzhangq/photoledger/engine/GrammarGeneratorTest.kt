package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrammarGeneratorTest {

    private val schema = ExtractionSchema(
        categories = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他"),
    )

    @Test
    fun `生成的文法以 root 规则开头`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        assertTrue(grammar.startsWith("root ::="), "应从 root ::= 开始，实际：${grammar.take(50)}")
    }

    @Test
    fun `字符串值强制 JSON 转义安全字符`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        // merchant 是自由文本（中文商家名），grammar 中不能直接允许裸引号/裸反斜杠
        val stringRule = grammar.lineSequence().first { it.startsWith("string ::=") }
        // 允许常见 CJK 与 ASCII 可打印字符，禁止未转义的 " 与 \
        assertTrue("[^\"\\\\\\x7F\\x00-\\x1F]" in stringRule, "string 规则应限制字符集：$stringRule")
    }

    @Test
    fun `枚举字段生成字面量交替`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        val currencyRule = grammar.lineSequence().first { it.startsWith("currency ::=") }
        assertEquals("""currency ::="\"CNY\"" | "\"USD\"" | "\"EUR\"" | "\"JPY\"" | "\"GBP\"" | "\"HKD\"" | "\"TWD\"" | "\"KRW\"" | "\"OTHER\""""", currencyRule)
    }

    @Test
    fun `类别枚举跟随用户类别列表`() {
        val custom = ExtractionSchema(categories = listOf("吃饭", "剁手"))
        val grammar = GrammarGenerator.fromSchema(custom)
        val categoryRule = grammar.lineSequence().first { it.startsWith("category ::=") }
        assertEquals("""category ::="\"吃饭\"" | "\"剁手\""""", categoryRule)
    }

    @Test
    fun `金额规则只产生合法数字`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        val amountRule = grammar.lineSequence().first { it.startsWith("amount ::= ") }
        assertTrue(amountRule.contains("[0-9]"), "金额须以数字开头：$amountRule")
    }

    @Test
    fun `日期字段为固定格式字符串`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        val dateRule = grammar.lineSequence().first { it.startsWith("date ::= ") }
        // "YYYY-MM-DD HH:MM:SS" 固定格式，方便解析与口径归一
        assertEquals(
            """date ::= "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-9] [0-9] "-" [0-9] [0-9] (" " [0-9] [0-9] ":" [0-9] [0-9] ":" [0-9] [0-9])? "\""""",
            dateRule,
        )
    }

    @Test
    fun `布尔与 null 不出现在文法中`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        assertTrue(!grammar.contains("true"), "null/true/false 不应是合法输出")
    }
}
