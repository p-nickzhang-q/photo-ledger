// 票 21：流水搜索纯函数。客户端内存过滤（本地账目量级下无感知延迟），
// 不加 DB 查询——Room LIKE 索引在这个数据规模是负收益。
package io.github.pnickzhangq.photoledger.data

import java.util.Locale

/**
 * 包含式过滤：商家 / 类别（不分大小写）、金额（两位小数字符串 contains，
 * 输入 "29.9" 可命中 29.90）、日期（datePaid contains，"09-18"、"2026" 均可）。
 * 空查询原样返回。
 */
fun filterEntries(entries: List<Entry>, query: String): List<Entry> {
    val q = query.trim()
    if (q.isEmpty()) return entries
    return entries.filter { e ->
        e.merchant.contains(q, ignoreCase = true) ||
            e.category.contains(q, ignoreCase = true) ||
            String.format(Locale.US, "%.2f", e.amountPaid).contains(q) ||
            e.datePaid.contains(q)
    }
}

/** 搜索结果的月头合计（口径同票 09 月度合计，只是来源换成过滤后的内存列表）。 */
fun searchMonthTotals(entries: List<Entry>): List<MonthTotal> =
    entries.groupBy { it.datePaid.take(7) }
        .map { (month, es) -> MonthTotal(month, es.sumOf { it.amountPaid }, es.size) }
        .sortedByDescending { it.month }
