// 票 06：账目库。schema v2（票 08）：+ categories 表（类别体系）。
// v1→v2 迁移只建表，不触碰既有 entries 数据；种子八类在 onOpen 空表时补插
// （首次安装与迁移后都走同一条种子路径）。
// v3（票 27）：+ merchant_memory 表（商户记忆），只建表不碰既有数据。
package io.github.pnickzhangq.photoledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pnickzhangq.photoledger.engine.DEFAULT_CATEGORIES
import com.pnickzhangq.photoledger.engine.DEFAULT_MERCHANT_MEMORY

@Database(
    entities = [Entry::class, CategoryEntity::class, MerchantMemoryEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun entryDao(): EntryDao

    abstract fun categoryDao(): CategoryDao

    abstract fun merchantMemoryDao(): MerchantMemoryDao

    companion object {
        private const val NAME = "ledger.db"

        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        /** v1（仅 entries）→ v2（+categories）。建表语句须与 Room 对 CategoryEntity 的预期一致。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
            }
        }

        /** v2 → v3（+merchant_memory）。只建表，既有 entries/categories 不动。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `merchant_memory` (" +
                        "`alias` TEXT NOT NULL, " +
                        "`canonical` TEXT NOT NULL, " +
                        "`category` TEXT, " +
                        "`hitCount` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`alias`))",
                )
            }
        }

        /** 空表种子八类与商户记忆（onOpen 每次开库检查，幂等——只在 count==0 时插）。 */
        private val SEED_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                val isEmpty = db.query("SELECT COUNT(*) FROM categories").use { c ->
                    c.moveToFirst()
                    c.getInt(0) == 0
                }
                if (isEmpty) {
                    DEFAULT_CATEGORIES.forEachIndexed { i, name ->
                        val stmt = db.compileStatement("INSERT INTO categories (name, sort_order) VALUES (?, ?)")
                        stmt.bindString(1, name)
                        stmt.bindLong(2, i.toLong())
                        stmt.executeInsert()
                    }
                }
                // 票 27：商户记忆空表时种子品牌字典（用户确认过的商户后续 upsert 覆盖计数）
                val memEmpty = db.query("SELECT COUNT(*) FROM merchant_memory").use { c ->
                    c.moveToFirst()
                    c.getInt(0) == 0
                }
                if (memEmpty) {
                    DEFAULT_MERCHANT_MEMORY.forEach { (alias, category) ->
                        val stmt = db.compileStatement(
                            "INSERT INTO merchant_memory (alias, canonical, category, hitCount, lastUsedAt) VALUES (?, ?, ?, 0, 0)",
                        )
                        stmt.bindString(1, alias)
                        stmt.bindString(2, alias)
                        stmt.bindString(3, category)
                        stmt.executeInsert()
                    }
                }
            }
        }

        /** 单例（App 进程内唯一）。 */
        fun get(context: Context): LedgerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(SEED_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }

        /** S3 测试用：内存库（每调用一个独立实例，同样种子）。 */
        fun inMemory(context: Context): LedgerDatabase =
            Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
                .allowMainThreadQueries()
                .addCallback(SEED_CALLBACK)
                .build()
    }
}
