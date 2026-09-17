// 票 08：类别实体（Room schema v2 起）。类别体系 = 用户可增删改的名称列表，
// Entry.category 以名称字符串引用（删类别时由仓储层级联归「其他」）。
package io.github.pnickzhangq.photoledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 类别名（唯一）。Entry.category 引用此名称。 */
    val name: String,

    /** 显示顺序（种子八类按内置顺序，新增追加尾部）。 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
)
