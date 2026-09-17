// 票 06：账目库仓储。Draft→Entry 确认流的领域逻辑（S3 接缝测试对象）：
// 确认才落库、放弃不留痕、手工空表单可成 Entry、删除可选连图删。
// 票 08：+类别体系（增删改、删类别其下 Entry 归「其他」、重命名级联）。
// UI 只调本层，不直接碰 DAO——保证「放弃 = 什么都不发生」在仓库层可测。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.pnickzhangq.photoledger.engine.DateSource
import com.pnickzhangq.photoledger.engine.Draft
import com.pnickzhangq.photoledger.engine.FALLBACK_CATEGORY
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
    /** 数据库本体（类别增删改需要 withTransaction 跨表原子性）。 */
    private val db: LedgerDatabase,
    /** 照片存储（票 07 去重需要 photosDir；其余场景仍走本类方法）。 */
    val photoStore: PhotoStore,
) {

    private val dao = db.entryDao()
    private val categoryDao = db.categoryDao()

    val entries: Flow<List<Entry>> = dao.observeAll()

    /** 类别体系（票 08）：管理页与全 App 注入（提取 prompt/快选/编辑下拉）。 */
    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()

    /** 类别名称列表（提取器逐张实时取——管理页改完立即生效，无需重建管线）。 */
    suspend fun categoryNames(): List<String> = categoryDao.list().map { it.name }

    /**
     * 新增类别：尾部追加。重名（含与内置冲突）由唯一索引拒绝，转 IllegalArgumentException。
     * 类别名会注入 prompt 与 grammar（GBNF 字符串字面量/JSON Schema enum），引号与反斜杠会破坏转义，入口即拒。
     */
    suspend fun addCategory(name: String): Long = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "类别名不能为空" }
        require(!trimmed.any { it == '"' || it == '\\' }) { "类别名不能包含引号或反斜杠" }
        try {
            val nextOrder = (categoryDao.list().maxOfOrNull { it.sortOrder } ?: -1) + 1
            categoryDao.insert(CategoryEntity(name = trimmed, sortOrder = nextOrder))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            throw IllegalArgumentException("类别「$trimmed」已存在", e)
        }
    }

    /**
     * 重命名类别：级联更新其下 Entry 引用（否则 Entry 里留旧字符串成悬空名）。
     * 「其他」不可改名——删类别归「其他」的兜底约定以其名称为准。
     */
    suspend fun renameCategory(id: Long, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "类别名不能为空" }
        val old = categoryDao.byId(id) ?: return@withContext
        require(old.name != FALLBACK_CATEGORY) { "「其他」不可重命名" }
        try {
            db.withTransaction {
                categoryDao.update(old.copy(name = trimmed))
                categoryDao.reassignEntries(oldName = old.name, newName = trimmed)
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            throw IllegalArgumentException("类别「$trimmed」已存在", e)
        }
    }

    /**
     * 删除类别：其下 Entry 全部归「其他」（不丢账，S3 验收项）。「其他」本身不可删。
     */
    suspend fun deleteCategory(id: Long) = withContext(Dispatchers.IO) {
        val target = categoryDao.byId(id) ?: return@withContext
        require(target.name != FALLBACK_CATEGORY) { "「其他」不可删除" }
        db.withTransaction {
            categoryDao.reassignEntries(oldName = target.name, newName = FALLBACK_CATEGORY)
            categoryDao.delete(target)
        }
    }

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
