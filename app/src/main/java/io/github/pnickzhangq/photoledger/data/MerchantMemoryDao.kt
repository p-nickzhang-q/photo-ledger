// 票 27：商户记忆 DAO。全表量级 = 用户历史商户数（几百内），逐次提取全量拉取做本地匹配。
package io.github.pnickzhangq.photoledger.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MerchantMemoryDao {

    @Query("SELECT * FROM merchant_memory")
    suspend fun all(): List<MerchantMemoryEntity>

    @Query("SELECT * FROM merchant_memory WHERE alias = :alias")
    suspend fun byAlias(alias: String): MerchantMemoryEntity?

    @Upsert
    suspend fun upsert(entity: MerchantMemoryEntity)
}
