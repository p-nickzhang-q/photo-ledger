// 票 06：Entry DAO。查询保持最小集（列表/单条），汇总查询属票 09。
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
}
