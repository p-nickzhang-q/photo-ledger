// 票 41：识别引擎配置（端侧内置 / 云端 OpenAI 兼容）与本地持久化。
// Key 只落在应用私有 SharedPreferences；界面与日志都不得回显完整 Key。
package io.github.pnickzhangq.photoledger.cloud

import android.content.Context
import java.util.UUID

/** 识别引擎二选一（票 42 设置页切换）：默认端侧，云端需显式开启并同意告知。 */
enum class RecognitionEngine { DEVICE, CLOUD }

/**
 * 云端配置。[configured] 只要求地址与模型名：部分自建网关不校验 Key，
 * Key 留空时请求不带 Authorization 头。
 */
data class CloudConfig(
    val engine: RecognitionEngine = RecognitionEngine.DEVICE,
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    /**
     * 票 46：快速模式——DeepSeek 推理模型请求 reasoning_effort=none，直接输出 JSON。
     * 实测同一订单图 7.2-10.5s → 2.7-5.1s；复杂截图若识别不全可关闭。
     */
    val fastMode: Boolean = true,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    /** 当前引擎为云端且配置完整。 */
    val cloudReady: Boolean get() = engine == RecognitionEngine.CLOUD && configured
}

class CloudPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun config(): CloudConfig = CloudConfig(
        engine = if (prefs.getString(KEY_ENGINE, ENGINE_DEVICE) == ENGINE_CLOUD) {
            RecognitionEngine.CLOUD
        } else {
            RecognitionEngine.DEVICE
        },
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        fastMode = prefs.getBoolean(KEY_FAST_MODE, true),
    )

    fun save(config: CloudConfig) {
        prefs.edit()
            .putString(KEY_ENGINE, if (config.engine == RecognitionEngine.CLOUD) ENGINE_CLOUD else ENGINE_DEVICE)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_API_KEY, config.apiKey)
            .putBoolean(KEY_FAST_MODE, config.fastMode)
            .apply()
    }

    /**
     * 票 46：会话 ID 跨进程稳定（OpenCode 用它做路由与缓存优化）。
     * 每次安装生成一次，随后复用；不含任何用户数据。
     */
    fun sessionId(): String {
        prefs.getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SESSION, id).apply()
        return id
    }

    /** 首次开启云端的隐私告知是否已确认。 */
    fun consentDone(): Boolean = prefs.getBoolean(KEY_CONSENT, false)

    fun setConsentDone() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
    }

    private companion object {
        const val NAME = "cloud"
        const val KEY_ENGINE = "engine"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_CONSENT = "consent_done"
        const val KEY_FAST_MODE = "fast_mode"
        const val KEY_SESSION = "session_id"
        const val ENGINE_DEVICE = "device"
        const val ENGINE_CLOUD = "cloud"
    }
}
