package com.pnickzhangq.photoledger.engine.llamacpp

import com.pnickzhangq.photoledger.engine.LlmTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * 通过 llama-server 的 OpenAI 兼容 /v1/chat/completions 发送图像 + grammar。
 *
 * 多模态：messages content 数组携带 image_url（data URI，base64）。
 * 约束解码：grammar 字段（llama-server 私有扩展）直接吃 GBNF——引擎侧由 schema
 * 生成文法，不依赖 llama-server 内置的 schema→grammar 转换。
 */
class LlamaServerTransport(
    baseUrl: String,
    private val temperature: Double = 0.1,
    private val maxTokens: Int = 512,
    private val timeoutSeconds: Long = 300,
) : LlmTransport {

    private val url = "$baseUrl/v1/chat/completions"

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(
        imageData: ByteArray,
        imageMime: String,
        prompt: String,
        grammar: String,
    ): String = withContext(Dispatchers.IO) {
        val content: kotlinx.serialization.json.JsonElement =
            if (imageData.isEmpty()) {
                // 纯文本调用（OCR 路线）：content 直接是字符串
                kotlinx.serialization.json.JsonPrimitive(prompt)
            } else {
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:$imageMime;base64," + Base64.getEncoder().encodeToString(imageData))
                        })
                    })
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    })
                }
            }

        val payload = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("grammar", grammar)
            put("cache_prompt", true)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("llama-server HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }

        val root = json.parseToJsonElement(response.body()).jsonObject
        root["choices"]!!.jsonArray
            .first().jsonObject["message"]!!
            .jsonObject["content"]!!
            .jsonPrimitive.content
    }
}
