// 票 41：OpenAI 兼容视觉接口客户端（chat/completions + data URL 图片）。
// 纯 JVM 实现（HttpURLConnection + kotlinx.serialization），post 可注入以便单测。
// 隐私：请求体（含截图 base64）与 Key 一律不落日志；错误只上抛用户可读文案。
package io.github.pnickzhangq.photoledger.cloud

import io.github.pnickzhangq.photoledger.data.TokenUsage
import com.pnickzhangq.photoledger.engine.Draft
import com.pnickzhangq.photoledger.engine.cloud.CloudDraftParser
import com.pnickzhangq.photoledger.engine.cloud.CloudEndpoint
import com.pnickzhangq.photoledger.engine.cloud.CloudParseException
import com.pnickzhangq.photoledger.engine.cloud.CloudPromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 云端识别失败（消息面向用户上屏，不含 Key / 请求体）。 */
class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** HTTP 结果（状态码 + 响应体；错误体由调用方按状态码判定，不直接上屏）。 */
data class HttpResponse(val code: Int, val body: String)

/** 票 45：一次云端识别 = 草稿列表 + token 用量（接口没返回 usage 时为空）。 */
data class CloudExtraction(val drafts: List<Draft>, val usage: TokenUsage? = null)

class CloudVisionClient(
    /**
     * 票 46：跨进程稳定的随机会话 ID（App 从 CloudPrefs 传入；单测走默认值）。
     * OpenCode Go 用它做路由与缓存优化；不含用户数据。
     */
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    /** 网关要求客户端自称（不接受 Python/Java 库的默认 UA）。 */
    private val userAgent: String = "photo-ledger/1.0",
    private val post: suspend (url: String, headers: Map<String, String>, body: String) -> HttpResponse = ::httpPost,
) {

    /**
     * 截图 JPEG（base64，无 data 前缀）→ Draft 列表（一图多单）。
     * 所有失败统一抛 [CloudException]，由提取器决定是否回退端侧。
     */
    suspend fun extract(imageBase64Jpeg: String, config: CloudConfig, categories: List<String>): CloudExtraction {
        val url = endpoint(config)
        val prompt = CloudPromptBuilder.build(categories)
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", 0)
            applySpeedParams(config)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:image/jpeg;base64,$imageBase64Jpeg")
                            }
                        }
                    }
                }
            }
        }.toString()

        val response = send(url, config.apiKey, body)
        requireSuccess(response)
        val root = responseRoot(response.body)
        val content = messageContent(root)
        if (content.isBlank()) throw CloudException("云端返回为空")
        val drafts = try {
            CloudDraftParser.parse(content, categories)
        } catch (e: CloudParseException) {
            throw CloudException("云端返回无法解析：${e.message}", e)
        }
        return CloudExtraction(drafts, parseUsage(root))
    }

    /** 测试连接：最小文本请求，只验证地址 / Key / 模型名可用。 */
    suspend fun ping(config: CloudConfig): String {
        val url = endpoint(config)
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", 0)
            applySpeedParams(config)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "只回复：OK")
                }
            }
        }.toString()
        val response = send(url, config.apiKey, body)
        requireSuccess(response)
        messageContent(responseRoot(response.body))
        return "连接成功"
    }

    /**
     * 票 46：DeepSeek V4 推理模型默认开思考，同一张图实测 7.2-10.5s；
     * reasoning_effort=none 后 2.7-5.1s、completion 从 356-544 降到 51，结果一致。
     * 只对 deepseek 模型发该参数，避免其它 OpenAI 兼容服务拒绝未知参数。
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.applySpeedParams(config: CloudConfig) {
        if (config.fastMode && config.model.contains("deepseek", ignoreCase = true)) {
            put("reasoning_effort", "none")
            put("max_tokens", 2048)
        }
    }

    private fun endpoint(config: CloudConfig): String = try {
        CloudEndpoint.chatCompletionsUrl(config.baseUrl)
    } catch (e: IllegalArgumentException) {
        throw CloudException(e.message ?: "接口地址无效", e)
    }

    private suspend fun send(url: String, apiKey: String, body: String): HttpResponse {
        val headers = buildMap {
            put("User-Agent", userAgent)
            if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            if (url.contains("opencode.ai")) put("x-opencode-session", sessionId)
        }
        return try {
            post(url, headers, body)
        } catch (e: SocketTimeoutException) {
            throw CloudException("云端请求超时", e)
        } catch (e: IOException) {
            throw CloudException("网络错误：${e.message ?: "连接失败"}", e)
        }
    }

    private fun requireSuccess(response: HttpResponse) {
        if (response.code in 200..299) return
        val detail = errorDetail(response.body)
        val suffix = if (detail.isBlank()) "" else "：$detail"
        when {
            response.code == 401 || response.code == 403 ->
                throw CloudException("API Key 无效或无权限（HTTP ${response.code}）$suffix")
            else ->
                throw CloudException("云端接口返回 HTTP ${response.code}$suffix")
        }
    }

    /** 只取服务端 error.message（截断 120 字）——不把原始响应体/请求体带上屏或日志。 */
    private fun errorDetail(body: String): String {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject ?: return ""
        } catch (e: Exception) {
            return ""
        }
        val error = root["error"] as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.content
            ?: (root["message"] as? JsonPrimitive)?.content
            ?: ""
        return message.take(120)
    }

    private fun responseRoot(body: String): JsonObject = try {
        Json.parseToJsonElement(body) as? JsonObject ?: throw CloudException("云端响应不是 JSON 对象")
    } catch (e: CloudException) {
        throw e
    } catch (e: Exception) {
        throw CloudException("云端响应不是 JSON", e)
    }

    /** usage.prompt/completion/total；兼容 input/output_tokens 命名，total 缺失时相加补。 */
    private fun parseUsage(root: JsonObject): TokenUsage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val prompt = intValue(usage["prompt_tokens"]) ?: intValue(usage["input_tokens"])
        val completion = intValue(usage["completion_tokens"]) ?: intValue(usage["output_tokens"])
        val total = intValue(usage["total_tokens"])
            ?: if (prompt != null && completion != null) prompt + completion else null
        if (prompt == null && completion == null && total == null) return null
        return TokenUsage(prompt, completion, total)
    }

    private fun intValue(e: kotlinx.serialization.json.JsonElement?): Int? = when (e) {
        is JsonPrimitive -> e.content.toDoubleOrNull()?.toInt()
        else -> null
    }

    /** choices[0].message.content；兼容 content 为文本数组/对象的分片形态。 */
    private fun messageContent(root: JsonObject): String {
        val choices = root["choices"] as? JsonArray
            ?: throw CloudException("云端响应缺少 choices")
        val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: throw CloudException("云端响应缺少 message")
        return when (val content = message["content"]) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.content
            }.joinToString("\n")
            is JsonObject -> (content["text"] as? JsonPrimitive)?.content.orEmpty()
            else -> ""
        }
    }
}

/** 默认实现：HttpURLConnection；只读重试交给用户重选截图，不做隐式重试。 */
private suspend fun httpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
): HttpResponse = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        HttpResponse(code, text)
    } finally {
        conn.disconnect()
    }
}
