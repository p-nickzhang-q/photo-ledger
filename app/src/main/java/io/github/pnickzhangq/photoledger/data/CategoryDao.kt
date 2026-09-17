// 票 08：类别 DAO。重命名级联 / 删类别归「其他」的跨表语句放这里（单条 SQL 自带原子性）。
package io.github.pnickzhangq.photoledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    /** 类别列表（管理页与全 App 注入用），按显示顺序。 */
    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sort_order ASC, id ASC")
    suspend fun list(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    /** 当前条数（种子判断用）。 */
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    /** 重命名级联：类别的旧名在 Entry 中的引用一并更新。 */
    @Query("UPDATE entries SET category = :newName WHERE category = :oldName")
    suspend fun reassignEntries(oldName: String, newName: String)
}
