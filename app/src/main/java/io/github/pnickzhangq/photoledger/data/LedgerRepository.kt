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
            // 一图多单确认（票 07 扩展）会毫秒级连续落盘：时间戳撞名时追加序号，避免互相覆盖
            val stamp = System.currentTimeMillis()
            var name = "$stamp.png"
            var seq = 0
            while (photosDir.resolve(name).exists() || thumbsDir.resolve(name).exists()) {
                seq++
                name = "$stamp-$seq.png"
            }
            val photo = File(photosDir, name)
            val thumbFile = File(thumbsDir, name)
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

    // ---- 汇总（票 09，只读）----

    /** 各月实付款合计（月倒序）。 */
    val monthTotals: Flow<List<MonthTotal>> = dao.observeMonthTotals()

    /** 某月各类别实付款合计（金额倒序，含「其他」）。 */
    fun categoryTotals(month: String): Flow<List<CategoryTotal>> = dao.observeCategoryTotals(month)

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

    // ---- 备份与恢复（票 11，S4 接缝）----

    /** 备份导出用：全量快照（Entry + 全部类别，照片文件不在备份范围）。 */
    suspend fun snapshot(): Pair<List<Entry>, List<CategoryEntity>> = withContext(Dispatchers.IO) {
        dao.list() to categoryDao.list()
    }

    /**
     * 备份恢复：事务内清表后按备份重建。Entry id 原样保回（含 createdAt/modifiedAt），
     * 类别 id 重发（无外部引用）。语义 = 替换式恢复（round-trip 无损的基准场景）。
     */
    suspend fun restore(data: BackupData) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.clearAll()
            categoryDao.clearAll()
            data.categories.forEach { categoryDao.insert(CategoryEntity(name = it.name, sortOrder = it.sortOrder)) }
            data.toEntryList().forEach { dao.insert(it) }
        }
    }

    /**
     * 完整备份打包（票 15）：json + 照片/缩略图入 zip。照片只收 Entry 引用的文件，
     * 缺失跳过。返回 (账目数, 照片数) 供成功提示。
     */
    suspend fun exportBackupZip(out: java.io.OutputStream): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val (entries, categories) = snapshot()
        val json = BackupCodec.encode(
            BackupCodec.fromEntities(
                entries, categories,
                java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            ),
        )
        val photos = entries.mapNotNull { it.photoPath }.distinct()
            .map { it to File(photoStore.photosDir, it) }.filter { it.second.exists() }
        val thumbs = entries.mapNotNull { it.thumbPath }.distinct()
            .map { it to File(photoStore.thumbsDir, it) }.filter { it.second.exists() }
        BackupZip.write(out, json, photos, thumbs)
        entries.size to photos.size
    }

    /**
     * 完整备份恢复：照片文件先落盘（photoPath/thumbPath 名字即相对路径），
     * 再走 DB 替换式恢复——文件先于库，失败不产生半截 DB 状态。
     */
    suspend fun restoreBackupZip(json: String, photos: Map<String, ByteArray>, thumbs: Map<String, ByteArray>) =
        withContext(Dispatchers.IO) {
            photos.forEach { (name, bytes) -> File(photoStore.photosDir, name).writeBytes(bytes) }
            thumbs.forEach { (name, bytes) -> File(photoStore.thumbsDir, name).writeBytes(bytes) }
            restore(BackupCodec.decode(json))
        }
}
