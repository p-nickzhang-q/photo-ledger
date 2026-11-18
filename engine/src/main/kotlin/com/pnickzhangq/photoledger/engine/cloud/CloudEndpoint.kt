package com.pnickzhangq.photoledger.engine.cloud

/**
 * 票 39：用户填写的 Base URL → OpenAI 兼容 chat/completions 端点。
 * 只接受 https：API Key 走 Authorization 头，明文地址会让 Key 裸奔。
 * 允许三种填法：裸 host、以 /v1 结尾、直接贴完整 /chat/completions。
 */
object CloudEndpoint {

    fun chatCompletionsUrl(base: String): String {
        val b = base.trim().removeSuffix("/")
        require(b.startsWith("https://")) { "接口地址必须以 https:// 开头" }
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }
}
