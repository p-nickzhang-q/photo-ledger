// 票 45：云端识别的 token 用量。旧账目（v5 前）没有该数据，保持 null 不补。
// 一张截图多单时，每个订单记录同一份用量（API 按图片计费，不做拆分）。
package io.github.pnickzhangq.photoledger.data

import java.util.Locale

data class TokenUsage(
    val prompt: Int? = null,
    val completion: Int? = null,
    val total: Int? = null,
) {
    val isEmpty: Boolean get() = prompt == null && completion == null && total == null

    /** 「输入 1,234 / 输出 156 / 总计 1,390 tokens」；缺失项跳过。 */
    fun display(): String {
        val parts = buildList {
            prompt?.let { add("输入 ${format(it)}") }
            completion?.let { add("输出 ${format(it)}") }
            total?.let { add("总计 ${format(it)}") }
        }
        return if (parts.isEmpty()) "" else parts.joinToString(" / ") + " tokens"
    }

    private fun format(n: Int): String = String.format(Locale.US, "%,d", n)
}

/** 旧账目三列全空 → null（详情页据此不显示用量）。 */
val Entry.tokenUsage: TokenUsage?
    get() = if (promptTokens == null && completionTokens == null && totalTokens == null) {
        null
    } else {
        TokenUsage(promptTokens, completionTokens, totalTokens)
    }
