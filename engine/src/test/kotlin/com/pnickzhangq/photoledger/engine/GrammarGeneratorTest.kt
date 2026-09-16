package com.pnickzhangq.photoledger.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        // 票 07 提速：契约收敛为 4 字段，currency/dateSource 不再出现在文法中
        val categoryRule = grammar.lineSequence().first { it.startsWith("category ::=") }
        assertEquals("""category ::="\"餐饮\"" | "\"购物\"" | "\"交通\"" | "\"居住\"" | "\"医疗\"" | "\"娱乐\"" | "\"通讯\"" | "\"其他\""""", categoryRule)
        assertTrue(grammar.lineSequence().none { it.startsWith("currency ::=") }, "币种不再是输出字段")
        assertTrue(grammar.lineSequence().none { it.startsWith("dateSource ::=") }, "口径不再是输出字段")
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
    fun `日期字段只到天`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        val dateRule = grammar.lineSequence().first { it.startsWith("date ::= ") }
        // 票 07 提速：datePaid 只输出 YYYY-MM-DD，时分秒由 OCR 清单来，不让模型逐字生成
        assertTrue(
            dateRule.contains("year \"-\" month \"-\" day") && !dateRule.contains("hour"),
            "date 规则应为纯日期：$dateRule",
        )
        val monthRule = grammar.lineSequence().first { it.startsWith("month ::= ") }
        assertTrue("\"1\" [0-2]" in monthRule, "月份须约束 01-12：$monthRule")
        val dayRule = grammar.lineSequence().first { it.startsWith("day ::= ") }
        assertTrue("\"3\" [0-1]" in dayRule, "日期须约束 01-31：$dayRule")
        assertTrue(grammar.lineSequence().none { it.startsWith("hour ::= ") }, "时间规则应已移除")
    }

    @Test
    fun `布尔与 null 不出现在文法中`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        assertTrue(!grammar.contains("true"), "null/true/false 不应是合法输出")
    }

    // ---------- OCR 路线：日期选择约束（票 12） ----------

    @Test
    fun `日期候选清单替换 date 规则`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        val constrained = GrammarGenerator.withDateAlternatives(
            grammar,
            listOf("2026-09-10 11:19:00", "2026-09-10 00:00:00"),
        )
        val dateRule = constrained.lineSequence().first { it.startsWith("date ::= ") }
        assertEquals(
            """date ::= "\"2026-09-10 11:19:00\"" | "\"2026-09-10 00:00:00\"" | "\"\""""",
            dateRule,
        )
        // 其他规则不受影响
        assertTrue(constrained.lineSequence().any { it.startsWith("month ::= ") })
    }

    @Test
    fun `空候选清单被拒绝`() {
        val grammar = GrammarGenerator.fromSchema(schema)
        assertFailsWith<IllegalArgumentException> {
            GrammarGenerator.withDateAlternatives(grammar, emptyList())
        }
    }

    // ---------- GBNF → JSON Schema（LiteRT-LM 约束用，票 13/14/07） ----------

    @Test
    fun `候选清单文法转 schema——datePaid 枚举含空串与候选`() {
        val grammar = GrammarGenerator.withDateAlternatives(
            GrammarGenerator.fromSchema(schema),
            listOf("2026-09-10 11:19:00", "2026-09-10 00:00:00"),
        )
        val schemaJson = Json.parseToJsonElement(GrammarGenerator.toJsonSchema(grammar)).jsonObject
        val datePaid = schemaJson["properties"]!!.jsonObject["datePaid"]!!.jsonObject
        assertEquals(
            listOf("", "2026-09-10 11:19:00", "2026-09-10 00:00:00"),
            datePaid["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `无日期候选（原始范围规则）转 schema——datePaid 自由 string 且整体合法 JSON`() {
        // 回归（票 07 真机）：无日期截图时范围规则曾被误当候选清单解析，
        // 规则文本里的裸引号产出非法 schema，LiteRT 侧 Gson 报 Unterminated array
        val grammar = GrammarGenerator.fromSchema(schema)
        val schemaJson = Json.parseToJsonElement(GrammarGenerator.toJsonSchema(grammar)).jsonObject
        val datePaid = schemaJson["properties"]!!.jsonObject["datePaid"]!!.jsonObject
        assertEquals("string", datePaid["type"]!!.jsonPrimitive.content)
        assertFalse("enum" in datePaid, "datePaid 不应有 enum：$datePaid")
    }

    @Test
    fun `schema 必填字段为契约四字段`() {
        val grammar = GrammarGenerator.withDateAlternatives(
            GrammarGenerator.fromSchema(schema),
            listOf("2026-09-10"),
        )
        val schemaJson = Json.parseToJsonElement(GrammarGenerator.toJsonSchema(grammar)).jsonObject
        val props = schemaJson["properties"]!!.jsonObject
        assertEquals(
            setOf("merchant", "amountPaid", "datePaid", "category"),
            props.keys,
        )
        assertEquals(
            listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他"),
            props["category"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            setOf("merchant", "amountPaid", "datePaid", "category"),
            schemaJson["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }
}
