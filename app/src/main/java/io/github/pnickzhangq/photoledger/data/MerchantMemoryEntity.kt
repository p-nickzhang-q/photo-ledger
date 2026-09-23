// 票 27：商户记忆（Room schema v3 起）。用户确认过的商户 → 后续截图 OCR 行
// 本地匹配自动回填 Draft.merchant。一行一个别名（canonical 自身也占一行）；
// OCR 变体不做存储，由 MerchantMatcher 模糊匹配（等长窗口距离 ≤1）覆盖。
package io.github.pnickzhangq.photoledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "merchant_memory")
data class MerchantMemoryEntity(
    /** 别名（当前实现 = canonical 自身，归一后的确认商户名）。 */
    @PrimaryKey val alias: String,
    /** 回填用的商户名。 */
    val canonical: String,
    /** 学习时顺带确认的类别（可空，暂未消费，留作后续回填 category）。 */
    val category: String? = null,
    /** 命中/学习次数（诊断用）。 */
    val hitCount: Int = 0,
    /** 最近使用时间戳（诊断用）。 */
    val lastUsedAt: Long = 0,
)
