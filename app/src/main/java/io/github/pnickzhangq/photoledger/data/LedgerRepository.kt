// 票 06：账目库仓储。Draft→Entry 确认流的领域逻辑（S3 接缝测试对象）：
// 确认才落库、放弃不留痕、手工空表单可成 Entry、删除可选连图删。
// UI 只调本层，不直接碰 DAO——保证「放弃 = 什么都不发生」在仓库层可测。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** 照片存储布局（App 私有目录下）：photos/ 原图、thumbs/ 缩略图。 */
class PhotoStore(private val rootDir: File) {

    val photosDir: File get() = File(rootDir, "photos").apply { mkdirs() }
    val thumbsDir: File get() = File(rootDir, "thumbs").apply { mkdirs() }

    /** 保存原图字节与缩略图，返回两相对路径；bytes 为 null（手工无图）返回 null to null。 */
    suspend fun save(original: ByteArray?, thumb: Bitmap?): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            if (original == null) return@withContext null to null
            val stamp = System.currentTimeMillis()
            val photo = File(photosDir, "$stamp.png")
            val thumbFile = File(thumbsDir, "$stamp.png")
            photo.writeBytes(original)
            thumb?.let { t ->
                thumbFile.outputStream().use { t.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }
            photo.name to thumbFile.takeIf { thumb != null }?.name
        }

    /** 读原图（确认界面对照用）。 */
    suspend fun readPhoto(relPath: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(File(photosDir, relPath).absolutePath) }.getOrNull()
    }

    /** 删除一对文件（存在才删，幂等）。 */
    suspend fun delete(photoPath: String?, thumbPath: String?) = withContext(Dispatchers.IO) {
        photoPath?.let { File(photosDir, it).takeIf(File::exists)?.delete() }
        thumbPath?.let { File(thumbsDir, it).takeIf(File::exists)?.delete() }
    }
}

class LedgerRepository(
    private val dao: EntryDao,
    private val photoStore: PhotoStore,
) {

    val entries: Flow<List<Entry>> = dao.observeAll()

    /**
     * 确认 Draft → 落库 Entry（CONTEXT.md「Draft」：确认后才成为 Entry）。
     * photoBytes 为原始截图（可 null——导入无图场景）；字段以确认界面编辑后的值为准。
     */
    suspend fun confirm(
        draft: Draft,
        editedMerchant: String = draft.merchant,
        editedAmount: Double = draft.amountPaid,
        editedDate: String = draft.datePaid,
        editedCategory: String = draft.category,
        editedCurrency: String = draft.currency,
        editedOrderStatus: String = draft.orderStatus,
        photoBytes: ByteArray? = null,
        thumb: Bitmap? = null,
    ): Long {
        val (photoPath, thumbPath) = photoStore.save(photoBytes, thumb)
        return dao.insert(
            Entry(
                merchant = editedMerchant,
                amountPaid = editedAmount,
                currency = editedCurrency,
                datePaid = editedDate,
                dateSource = draft.dateSource.name,
                orderStatus = editedOrderStatus,
                category = editedCategory,
                photoPath = photoPath,
                thumbPath = thumbPath,
            ),
        )
    }

    /** 手工新增（无截图）。字段可为空——「手工空表单可成 Entry」（S3）。 */
    suspend fun addManual(
        merchant: String,
        amountPaid: Double,
        datePaid: String,
        category: String,
        currency: String = "CNY",
        dateSource: DateSource = DateSource.ORDER_TIME,
        orderStatus: String = "",
    ): Long = dao.insert(
        Entry(
            merchant = merchant,
            amountPaid = amountPaid,
            currency = currency,
            datePaid = datePaid,
            dateSource = dateSource.name,
            orderStatus = orderStatus,
            category = category,
            photoPath = null,
            thumbPath = null,
        ),
    )

    /** 放弃 Draft：什么都不发生（S3：放弃不留痕）。显式存在以承载测试断言。 */
    fun discard() = Unit

    /** 编辑 Entry（modifiedAt 刷新）。 */
    suspend fun edit(entry: Entry, changes: Entry.() -> Entry) {
        dao.update(entry.run(changes).let { it.copy(modifiedAt = System.currentTimeMillis()) })
    }

    /**
     * 删除 Entry。deletePhoto=true 时连原图与缩略图一起删（票 06 验收项）；
     * false 时保留文件（照片可能被别的 Entry 引用？v1 一图一 Entry，保留即孤儿，由用户择）。
     */
    suspend fun delete(entry: Entry, deletePhoto: Boolean) {
        dao.delete(entry)
        if (deletePhoto) {
            photoStore.delete(entry.photoPath, entry.thumbPath)
        }
    }
}
