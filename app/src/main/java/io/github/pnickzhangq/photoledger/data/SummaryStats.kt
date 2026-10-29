// 票 22：汇总页轻量统计纯函数。口径 = Entry.amountPaid（实付款）；
// 商家退出模型识别后多为手填，单笔标注回退链 商家 → 类别。
package io.github.pnickzhangq.photoledger.data

/** 当月单笔画像：平均单笔 + 最高单笔（标注为 商家→类别 回退）。 */
data class MonthStats(
    val avgAmount: Double,
    val maxAmount: Double,
    val maxLabel: String,
)

/** 上月对比结果：previous 为 null 表示上月无数据（UI 不显示环比行）。 */
data class MonthOverMonth(
    val current: Double,
    val previous: Double?,
) {
    /** 变化百分比（上月为 0 或缺失返回 null）；正 = 比上月多花。 */
    val pctChange: Double?
        get() {
            val prev = previous ?: return null
            if (prev == 0.0) return null
            return (current - prev) / prev * 100
        }
}

fun monthOverMonth(current: Double, previous: Double?): MonthOverMonth =
    MonthOverMonth(current, previous)

fun monthStats(entries: List<Entry>): MonthStats? {
    if (entries.isEmpty()) return null
    val total = entries.sumOf { it.amountPaid }
    val max = entries.maxBy { it.amountPaid }
    val label = max.merchant.ifBlank { max.category.ifBlank { "（未命名）" } }
    return MonthStats(
        avgAmount = total / entries.size,
        maxAmount = max.amountPaid,
        maxLabel = label,
    )
}

/** 票 35：汇总页下钻——账目按月分组（月键 = datePaid 前 7 位；空日期归 ""，与 MonthTotal 同口径）。 */
fun entriesByMonth(entries: List<Entry>): Map<String, List<Entry>> =
    entries.groupBy { it.datePaid.take(7) }

/** 票 35：汇总页下钻——某月按类别分组的账目（月内保持入参顺序，即 date_paid 倒序）。 */
fun entriesByCategory(entries: List<Entry>, month: String): Map<String, List<Entry>> =
    entriesByMonth(entries)[month].orEmpty().groupBy { it.category }
