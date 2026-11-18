// 票 41：云端客户端单测（假 post，不触网）。
// 覆盖：成功解析、请求形态（URL/鉴权/data URL）、401、5xx、非 JSON、空订单、超时、明文地址。
package io.github.pnickzhangq.photoledger.cloud

import io.github.pnickzhangq.photoledger.data.TokenUsage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class CloudVisionClientTest {

    private val categories = listOf("餐饮", "购物", "交通", "居住", "医疗", "娱乐", "通讯", "其他")
    private val config = CloudConfig(
        engine = RecognitionEngine.CLOUD,
        baseUrl = "https://api.example.com/v1",
        model = "vision-model",
        apiKey = "sk-test",
    )

    private fun okBody(content: String, withUsage: Boolean = false): String = buildJsonObject {
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") { put("content", content) }
            }
        }
        if (withUsage) {
            putJsonObject("usage") {
                put("prompt_tokens", 1200)
                put("completion_tokens", 150)
                put("total_tokens", 1350)
            }
        }
    }.toString()

    @Test
    fun `成功解析并发出正确请求形态`() = runTest {
        var url = ""
        var headers = emptyMap<String, String>()
        var body = ""
        val orders = """{"orders":[{"merchant":"瑞幸咖啡","content":["冰美式×2"],""" +
            """"amountPaid":29.9,"datePaid":"2026-11-18","category":"餐饮"}]}"""
        val client = CloudVisionClient { u, h, b ->
            url = u
            headers = h
            body = b
            HttpResponse(200, okBody(orders, withUsage = true))
        }

        val result = client.extract("QUJD", config, categories)

        assertEquals("https://api.example.com/v1/chat/completions", url)
        assertEquals("Bearer sk-test", headers["Authorization"])
        assertTrue("x-opencode-session" !in headers) // 非 opencode 端点不塞私有头
        assertTrue(body.contains("vision-model"))
        assertTrue(body.contains("data:image/jpeg;base64,QUJD"))
        assertTrue(body.contains("餐饮"))
        assertEquals("photo-ledger/1.0", headers["User-Agent"])
        assertTrue("\"reasoning_effort\"" !in body) // 非 deepseek 模型不发私有参数
        assertEquals("瑞幸咖啡", result.drafts.single().merchant)
        assertEquals("冰美式×2", result.drafts.single().content)
        assertEquals(29.9, result.drafts.single().amountPaid, 1e-9)
        assertEquals(TokenUsage(1200, 150, 1350), result.usage)
    }

    @Test
    fun `快速模式对 deepseek 关闭思考并限制输出`() = runTest {
        var body = ""
        val client = CloudVisionClient { _, _, b ->
            body = b
            HttpResponse(200, okBody("""{"orders":[{"amountPaid":1,"category":"餐饮"}]}"""))
        }
        client.extract(
            "QUJD",
            config.copy(model = "deepseek-v4.1-flash"),
            categories,
        )
        assertTrue(body.contains("\"reasoning_effort\":\"none\""))
        assertTrue(body.contains("\"max_tokens\":2048"))
    }

    @Test
    fun `关闭快速模式后不发送 reasoning_effort`() = runTest {
        var body = ""
        val client = CloudVisionClient { _, _, b ->
            body = b
            HttpResponse(200, okBody("""{"orders":[{"amountPaid":1,"category":"餐饮"}]}"""))
        }
        client.extract(
            "QUJD",
            config.copy(model = "deepseek-v4.1-flash", fastMode = false),
            categories,
        )
        assertTrue("\"reasoning_effort\"" !in body)
        assertTrue("\"max_tokens\"" !in body)
    }

    @Test
    fun `opencode 端点携带会话头`() = runTest {
        var headers = emptyMap<String, String>()
        val client = CloudVisionClient { _, h, _ ->
            headers = h
            HttpResponse(200, okBody("""{"orders":[{"amountPaid":1,"category":"餐饮"}]}"""))
        }
        client.extract(
            "QUJD",
            config.copy(baseUrl = "https://opencode.ai/zen/go/v1"),
            categories,
        )
        assertTrue(headers["x-opencode-session"].orEmpty().isNotBlank())
    }

    @Test
    fun `非 2xx 带出服务端 error message`() = runTest {
        val client = CloudVisionClient { _, _, _ ->
            HttpResponse(
                400,
                """{"type":"error","error":{"type":"MissingSessionID","message":"Request is missing x-opencode-session"}}""",
            )
        }
        try {
            client.extract("QUJD", config.copy(baseUrl = "https://opencode.ai/zen/go/v1"), categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("HTTP 400"))
            assertTrue(e.message!!.contains("x-opencode-session"))
        }
    }

    @Test
    fun `Key 为空时不发 Authorization 头`() = runTest {
        var headers = mapOf("x" to "y")
        val client = CloudVisionClient { _, h, _ ->
            headers = h
            HttpResponse(200, okBody("""{"orders":[{"amountPaid":1,"category":"餐饮"}]}"""))
        }
        client.extract("QUJD", config.copy(apiKey = ""), categories)
        assertTrue("Authorization" !in headers)
        assertEquals("photo-ledger/1.0", headers["User-Agent"])
    }

    @Test
    fun `401 提示 Key 无效`() = runTest {
        val client = CloudVisionClient { _, _, _ -> HttpResponse(401, "{}") }
        try {
            client.extract("QUJD", config, categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("API Key"))
        }
    }

    @Test
    fun `5xx 提示 HTTP 状态码`() = runTest {
        val client = CloudVisionClient { _, _, _ -> HttpResponse(503, "oops") }
        try {
            client.extract("QUJD", config, categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("HTTP 503"))
        }
    }

    @Test
    fun `非 JSON 响应与空订单都有可读原因`() = runTest {
        val garbage = CloudVisionClient { _, _, _ -> HttpResponse(200, "not json") }
        try {
            garbage.extract("QUJD", config, categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("不是 JSON"))
        }

        val empty = CloudVisionClient { _, _, _ -> HttpResponse(200, okBody("""{"orders":[]}""")) }
        try {
            empty.extract("QUJD", config, categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("订单"))
        }
    }

    @Test
    fun `超时与明文地址`() = runTest {
        val timeout = CloudVisionClient { _, _, _ -> throw SocketTimeoutException("timeout") }
        try {
            timeout.extract("QUJD", config, categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("超时"))
        }

        try {
            CloudVisionClient { _, _, _ -> HttpResponse(200, "{}") }
                .extract("QUJD", config.copy(baseUrl = "http://api.example.com"), categories)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("https://"))
        }
    }

    @Test
    fun `测试连接成功与失败`() = runTest {
        val ok = CloudVisionClient { _, _, _ -> HttpResponse(200, okBody("OK")) }
        assertEquals("连接成功", ok.ping(config))

        val unauthorized = CloudVisionClient { _, _, _ -> HttpResponse(403, "{}") }
        try {
            unauthorized.ping(config)
            throw AssertionError("应当抛出")
        } catch (e: CloudException) {
            assertTrue(e.message!!.contains("API Key"))
        }
    }
}
