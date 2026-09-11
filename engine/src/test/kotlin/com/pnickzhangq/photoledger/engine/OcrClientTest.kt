package com.pnickzhangq.photoledger.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** OCR 协议（docs/design/ocr-protocol.md）解析语义测试。 */
class OcrClientTest {

    private val client = OcrClient(workerScript = "unused", pythonBin = "unused")

    @Test
    fun `协议 v1 解析为结果列表`() {
        val raw = """
        {"version":1,"results":[
          {"image":"/a.jpg","ok":true,"lines":[
            {"text":"实付款￥13.6","score":0.98,"box":[[1,2],[3,4],[5,6],[7,8]]}
          ]},
          {"image":"/b.png","ok":false,"error":"ImageDecodeError: bad jpg"}
        ]}
        """.trimIndent()
        val results = client.parseResponse(raw)
        assertEquals(2, results.size)
        assertTrue(results[0].ok)
        assertEquals("实付款￥13.6", results[0].lines[0].text)
        assertEquals(0.98f, results[0].lines[0].score)
        assertTrue(!results[1].ok)
        assertEquals("ImageDecodeError: bad jpg", results[1].error)
    }

    @Test
    fun `版本不匹配显性失败`() {
        val e = assertFailsWith<OcrEngineException> {
            client.parseResponse("""{"version":2,"results":[]}""")
        }
        assertTrue("版本" in e.message!!)
    }

    @Test
    fun `非协议输出显性失败`() {
        assertFailsWith<OcrEngineException> {
            client.parseResponse("Traceback (most recent call last): ...")
        }
    }

    @Test
    fun `空白图 ok 为 true 行为空`() {
        val results = client.parseResponse("""{"version":1,"results":[{"image":"/c.png","ok":true,"lines":[]}]}""")
        assertTrue(results[0].ok)
        assertEquals(0, results[0].lines.size)
    }

    @Test
    fun `合成双订单样例走完 后处理-素材 全链`() {
        // 形态取自真实截图，数据虚构：两订单 + 日期角标碎片
        val sample = javaClass.getResourceAsStream("/ocr-samples/synthetic-duo-orders.json")!!
            .readBytes().decodeToString()
        val results = client.parseResponse(
            """{"version":1,"results":[{"image":"synthetic","ok":true,"lines":${sampleLines(sample)}}]}""",
        )
        val lines = results[0].lines.map {
            OcrLine(it.text, it.score, it.box.flatten())
        }
        assertEquals(2, OcrPostProcessor.splitOrderBlocks(lines).size)
        val material = OcrPostProcessor.buildStructuredMaterial(lines)
        // 角标 09.10 借上下文年份补全为完整日期
        assertTrue(material.normalizedDates.contains("2026-09-10 00:00:00"))
        // 完整日期行规范化
        assertTrue(material.normalizedDates.contains("2026-09-10 11:19:00"))
        // 两笔实付款都被提取
        assertTrue(material.amounts.contains(13.6))
        assertTrue(material.amounts.contains(17.1))
    }

    private fun sampleLines(sampleJson: String): String {
        // 从样例 JSON 里取 lines 数组原文（测试专用，样例是受控内容）
        val start = sampleJson.indexOf("\"lines\":") + "\"lines\":".length
        val depthStart = sampleJson.indexOf('[', start)
        var depth = 0
        for (i in depthStart until sampleJson.length) {
            when (sampleJson[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return sampleJson.substring(depthStart, i + 1) }
            }
        }
        throw IllegalStateException("样例格式错误")
    }

    private fun List<List<Float>>.flatten(): List<Float> = flatMap { it }
}
