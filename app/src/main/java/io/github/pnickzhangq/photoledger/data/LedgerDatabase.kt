// 票 06：账目库。schema 在本票定型（S3 接缝测试以内存库锁定行为）。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Entry::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao

    companion object {
        private const val NAME = "ledger.db"

        /** 单例（App 进程内唯一）。 */
        fun get(context: Context): LedgerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    NAME,
                ).build().also { INSTANCE = it }
            }

        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        /** S3 测试用：内存库（每调用一个独立实例）。 */
        fun inMemory(context: Context): LedgerDatabase =
            Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
