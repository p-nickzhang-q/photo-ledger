// 票 06：账目库实体（Room schema 在本票定型）。
// Entry = 确认后的账目记录（CONTEXT.md「Entry」）；金额口径实付款、日期口径付款时间优先。
package io.github.pnickzhangq.photoledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "entries")
data class Entry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 商家/店铺名（Draft 同名字段迁移或手填）。 */
    val merchant: String,

    /** 实付款金额（口径见 CONTEXT.md「实付款」，非商品总价）。 */
    @ColumnInfo(name = "amount_paid")
    val amountPaid: Double,

    /** 币种代码，默认 CNY。 */
    val currency: String = "CNY",

    /** 日期（口径：付款时间优先退下单时间），格式 yyyy-MM-dd HH:mm:ss。 */
    @ColumnInfo(name = "date_paid")
    val datePaid: String,

    /** 日期口径标注：PAYMENT_TIME / ORDER_TIME（引擎 DateSource 同构）。 */
    @ColumnInfo(name = "date_source")
    val dateSource: String,

    /** 订单状态原文（v1 仅展示，不参与账目逻辑）。 */
    @ColumnInfo(name = "order_status")
    val orderStatus: String,

    /** 类别。类别体系属票 08，本票为自由字符串（默认集与提取器一致）。 */
    val category: String,

    /** 原始截图在私有目录的相对路径；手工录入无截图时为 null。 */
    @ColumnInfo(name = "photo_path")
    val photoPath: String?,

    /** 缩略图在私有目录的相对路径；无截图时 null。 */
    @ColumnInfo(name = "thumb_path")
    val thumbPath: String?,

    /** 创建时间（epoch millis），列表倒序基准。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后编辑时间（epoch millis）。 */
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = createdAt,
)
