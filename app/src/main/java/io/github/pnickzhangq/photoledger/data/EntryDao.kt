// 票 06：Entry DAO。查询保持最小集（列表/单条）；票 09 增补汇总查询（无写路径）。
package io.github.pnickzhangq.photoledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {

    /** 流水列表：倒序（新建在前），按日期次序、同日期按 id。 */
    @Query("SELECT * FROM entries ORDER BY date_paid DESC, id DESC")
    fun observeAll(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: Long): Entry?

    /** 确认/手工新增。返回新行 id。 */
    @Insert
    suspend fun insert(entry: Entry): Long

    @Update
    suspend fun update(entry: Entry)

    /** 删除（照片文件清理由调用方负责——需知道是否连图删）。 */
    @Delete
    suspend fun delete(entry: Entry)

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun count(): Int

    /** 备份导出用：全量快照（按 id 序，保证编码稳定）。 */
    @Query("SELECT * FROM entries ORDER BY id ASC")
    suspend fun list(): List<Entry>

    /** 备份恢复用：清表（在 withTransaction 内调用）。 */
    @Query("DELETE FROM entries")
    suspend fun clearAll()

    // ---- 汇总（票 09）----

    /**
     * 月度合计。date_paid 存量两种格式（yyyy-MM-dd HH:mm:ss 与 yyyy-MM-dd），
     * substr 取前 7 位统一为 "yyyy-MM"；空串（手工空表单）归 "" 组。
     */
    @Query(
        "SELECT substr(date_paid, 1, 7) AS month, SUM(amount_paid) AS total, COUNT(*) AS count " +
            "FROM entries GROUP BY month ORDER BY month DESC",
    )
    fun observeMonthTotals(): Flow<List<MonthTotal>>

    /** 某月各类别实付款合计（含「其他」），金额倒序。 */
    @Query(
        "SELECT category, SUM(amount_paid) AS total " +
            "FROM entries WHERE substr(date_paid, 1, 7) = :month GROUP BY category ORDER BY total DESC",
    )
    fun observeCategoryTotals(month: String): Flow<List<CategoryTotal>>
}
