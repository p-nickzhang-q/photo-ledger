// 票 11：备份与导出。备份 = JSON 可出可进（Entry + 类别，不含照片，round-trip 无损是硬要求）；
// 导出 = CSV 只出不进（UTF-8 BOM 供 Excel，非恢复手段——备份与导出是词汇表里刻意分开的两个概念）。
// 本文件是纯 Kotlin 编解码（无 Android 依赖），S4 接缝测试对象。
package io.github.pnickzhangq.photoledger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 备份里的类别（不带 id：id 无外部引用，恢复时重发即可；name + sortOrder 才是要保真的）。 */
@Serializable
data class BackupCategory(
    val name: String,
    val sortOrder: Int,
)

/** 备份里的账目（Entry 全字段，含 id 与时间戳——恢复必须逐字段无损）。 */
@Serializable
data class BackupEntry(
    val id: Long,
    val merchant: String,
    val amountPaid: Double,
    val currency: String,
    val datePaid: String,
    val dateSource: String,
    val orderStatus: String,
    val category: String,
    val photoPath: String? = null,
    val thumbPath: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
)

@Serializable
data class BackupData(
    val format: String,
    val version: Int,
    val exportedAt: String,
    val categories: List<BackupCategory> = emptyList(),
    val entries: List<BackupEntry> = emptyList(),
)

object BackupCodec {

    const val FORMAT = "photo-ledger-backup"
    const val VERSION = 1
    const val CSV_BOM = "\uFEFF"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun fromEntities(entries: List<Entry>, categories: List<CategoryEntity>, exportedAt: String) =
        BackupData(
            format = FORMAT,
            version = VERSION,
            exportedAt = exportedAt,
            categories = categories.map { BackupCategory(it.name, it.sortOrder) },
            entries = entries.map {
                BackupEntry(
                    id = it.id,
                    merchant = it.merchant,
                    amountPaid = it.amountPaid,
                    currency = it.currency,
                    datePaid = it.datePaid,
                    dateSource = it.dateSource,
                    orderStatus = it.orderStatus,
                    category = it.category,
                    photoPath = it.photoPath,
                    thumbPath = it.thumbPath,
                    createdAt = it.createdAt,
                    modifiedAt = it.modifiedAt,
                )
            },
        )

    fun encode(data: BackupData): String = json.encodeToString(data)

    /** 解析并校验格式标签与版本。version 只接受 ≤ 当前（向前兼容读旧备份）。 */
    fun decode(text: String): BackupData {
        val data = try {
            json.decodeFromString<BackupData>(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是照片记账的备份文件（内容无法解析）", e)
        }
        require(data.format == FORMAT) { "不是照片记账的备份文件（format=${data.format}）" }
        require(data.version <= VERSION) { "备份版本过新（v${data.version}），请先升级 App" }
        return data
    }

    /**
     * CSV 导出（只出不进）：UTF-8 BOM 打头（Excel 打开中文不乱码），RFC 4180 转义。
     * 列为账目的人类可读字段；id / 照片内部路径不入 CSV（对 Excel 读者无意义）。
     */
    fun toCsv(entries: List<Entry>): String {
        val header = listOf("付款时间", "商家", "实付款", "币种", "类别", "日期口径", "订单状态", "创建时间", "修改时间")
        val rows = entries.map { e ->
            listOf(
                e.datePaid,
                e.merchant,
                e.amountPaid.toPlainString(),
                e.currency,
                e.category,
                displayDateSource(e.dateSource),
                e.orderStatus,
                formatMillis(e.createdAt),
                formatMillis(e.modifiedAt),
            )
        }
        return CSV_BOM + (listOf(header) + rows).joinToString("\r\n") { row ->
            row.joinToString(",") { csvEscape(it) }
        }
    }

    /** RFC 4180：含逗号/引号/换行才加引号，内部引号翻倍。 */
    internal fun csvEscape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v

    /** 金额不带科学计数、去掉多余的 .0（14.5 → "14.5"，15.0 → "15"）。 */
    internal fun Double.toPlainString(): String {
        val d = kotlin.math.abs(this % 1.0)
        return if (d < 1e-9) this.toLong().toString() else this.toString()
    }

    private fun displayDateSource(raw: String) = when (raw) {
        "PAYMENT_TIME" -> "付款时间"
        "ORDER_TIME" -> "下单时间"
        else -> raw
    }

    private fun formatMillis(ms: Long): String =
        if (ms <= 0) "" else java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

/** 恢复为 Entry（photoPath 原样保留——照片未备份，恢复后引用悬空由 UI 空态兜底）。 */
fun BackupData.toEntryList(): List<Entry> = entries.map {
    Entry(
        id = it.id,
        merchant = it.merchant,
        amountPaid = it.amountPaid,
        currency = it.currency,
        datePaid = it.datePaid,
        dateSource = it.dateSource,
        orderStatus = it.orderStatus,
        category = it.category,
        photoPath = it.photoPath,
        thumbPath = it.thumbPath,
        createdAt = it.createdAt,
        modifiedAt = it.modifiedAt,
    )
}
