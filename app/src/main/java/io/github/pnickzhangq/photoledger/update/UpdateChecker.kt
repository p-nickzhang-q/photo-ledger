// 票 31：应用内版本更新检查。唯一网络目标 = GitHub releases/latest（只读、
// 不带用户数据，隐私口径见工单/ADR-0003 注记）；失败静默，红点提示后跳浏览器
// 下载（不做应用内安装，避开 REQUEST_INSTALL_PACKAGES 复杂度）。
package io.github.pnickzhangq.photoledger.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
)

object UpdateChecker {

    /** Release 页（更新跳转用）。 */
    const val RELEASES_URL = "https://github.com/p-nickzhang-q/photo-ledger/releases/latest"

    private const val API_URL =
        "https://api.github.com/repos/p-nickzhang-q/photo-ledger/releases/latest"

    /** 检查结果态。Failed 不区分原因（网络/接口），UI 一律静默或 Toast。 */
    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val current: String) : State()
        data class Available(val latest: String, val url: String) : State()
        data class Failed(val reason: String) : State()
    }

    /**
     * 查询最新 Release 并与当前 versionName 比较。
     * currentVersion 形如 "0.4.9"（来自 packageManager，无需 BuildConfig）。
     */
    suspend fun check(currentVersion: String): State = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "photo-ledger-app")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = try {
                if (conn.responseCode != 200) return@withContext State.Failed("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val release = Json { ignoreUnknownKeys = true }.decodeFromString<GhRelease>(body)
            val latest = release.tagName.removePrefix("v").removePrefix("V")
            if (isNewer(latest, currentVersion)) {
                State.Available(latest = latest, url = release.htmlUrl.ifBlank { RELEASES_URL })
            } else {
                State.UpToDate(currentVersion)
            }
        } catch (t: Throwable) {
            State.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 数字段逐位比较（0.10.0 > 0.4.9，非字符串比较）；缺位补 0；
     * 非数字段当 0（容错 tag 尾缀）。latest > current 才算有更新。
     */
    fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
