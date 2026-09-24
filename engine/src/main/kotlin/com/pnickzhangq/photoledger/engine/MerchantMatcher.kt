package com.pnickzhangq.photoledger.engine

/**
 * 商户记忆匹配（票 27）：OCR 行对用户确认过的商户做本地匹配，命中回填
 * Draft.merchant——merchant 已退出模型输出（0.6B 抄写中文店名太弱），本匹配
 * 让用户填过的商户在下次截图自动回来，不占 LLM 算力。
 *
 * 两级匹配，宁缺勿错：
 * 1. contains：别名完整出现在行里，多命中取最长别名（最具体）
 * 2. 模糊：仅对 ≥3 字别名，等长滑窗编辑距离 ≤1——覆盖 OCR 单字误读
 *   （「蜜雪冰城」→「蜜雷冰城」）；距离阈值 1 而非 2，防「美团/滴滴」类
 *   短词互撞的误匹配
 * 全部纯函数，可独立单测。
 */
data class MerchantAlias(
    val alias: String,
    val canonical: String,
    /** 票 30：记忆顺带确认的类别（种子为品牌→内置八类映射；学习入口传确认值）。 */
    val category: String? = null,
)

object MerchantMatcher {

    /** 兼容入口：只要 canonical。 */
    fun match(lines: List<String>, aliases: List<MerchantAlias>): String? =
        matchDetail(lines, aliases)?.canonical

    /** 票 30：命中返回完整别名对象（canonical + category 供联动回填）。 */
    fun matchDetail(lines: List<String>, aliases: List<MerchantAlias>): MerchantAlias? {
        if (lines.isEmpty() || aliases.isEmpty()) return null
        val cleanLines = lines.map { it.replace(" ", "").replace("　", "") }.filter { it.length >= 2 }
        if (cleanLines.isEmpty()) return null

        // 1) contains：多命中取最长别名
        var bestLen = 0
        var best: MerchantAlias? = null
        for (a in aliases) {
            val alias = a.alias.replace(" ", "").replace("　", "")
            if (alias.length < 2) continue
            if (bestLen >= alias.length) continue
            if (cleanLines.any { it.contains(alias) }) {
                bestLen = alias.length
                best = a
            }
        }
        if (best != null) return best

        // 2) 模糊：等长窗口距离 ≤1。含数字/字母的短别名（711/12306/T3出行）禁用——
        //    日期时间行里的数字串（「2311」→ 711）会形成灾难性误命中（真机票29回归）
        for (a in aliases) {
            val alias = a.alias.replace(" ", "").replace("　", "")
            if (alias.length < 3) continue
            if (alias.any { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' }) continue
            if (cleanLines.any { line -> fuzzyContains(line, alias) }) return a
        }
        return null
    }

    /** 行内存在与别名等长（窗口可 ±1）、编辑距离 ≤1 的片段 → OCR 变体命中。 */
    private fun fuzzyContains(line: String, alias: String): Boolean {
        if (kotlin.math.abs(line.length - alias.length) <= 1 && editDistance(line, alias) <= 1) return true
        if (line.length < alias.length) return false
        for (len in (alias.length - 1).coerceAtLeast(2)..alias.length + 1) {
            if (len > line.length) continue
            for (start in 0..line.length - len) {
                if (editDistance(line.substring(start, start + len), alias) <= 1) return true
            }
        }
        return false
    }

    /** 经典 DP 编辑距离（插入/删除/替换，小数据量下性能无忧）。 */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, sub)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }
}
