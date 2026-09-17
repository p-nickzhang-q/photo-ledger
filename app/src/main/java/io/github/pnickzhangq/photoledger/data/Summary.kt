// 票 09：汇总查询 POJO。口径 = Entry.amountPaid（实付款）唯一参与统计；
// dateSource / orderStatus / currency 不进入汇总。
package io.github.pnickzhangq.photoledger.data

/** 月度合计。month 为 "yyyy-MM"；date_paid 为空串的历史数据归入 month="" 组（UI 显示「无日期」）。 */
data class MonthTotal(
    val month: String,
    val total: Double,
    val count: Int,
)

/** 某月各类别合计（含手工记录；「其他」为删类别兜底归入，不缺行）。 */
data class CategoryTotal(
    val category: String,
    val total: Double,
)
