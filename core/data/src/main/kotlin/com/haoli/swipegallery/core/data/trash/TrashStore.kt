package com.haoli.swipegallery.core.data.trash

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import kotlinx.coroutines.flow.Flow

/**
 * 应用自己的回收站记录。
 *
 * **为什么不依赖系统回收站**：
 *  1. 它在各厂商 ROM 上的表现不一致 —— 是否真的进回收站、回收站入口在哪儿，
 *     都因设备而异，用户常常找不到被移走的内容；
 *  2. 更关键的是它**不释放空间**，文件只是换个地方待着；
 *  3. 把「删除」绑在一个行为不可控的系统调用上，一旦不符合预期就是静默的数据丢失，
 *     而用户没有任何补救手段。
 *
 * 所以这里改成由应用自己记账：滑卡删除只是**记录**，文件原封不动。
 * 真正的销毁发生在用户在回收站里明确点「彻底删除」时。
 *
 * 代价要说清楚：记录期间文件仍占用磁盘、在系统相册里仍可见。
 * 这是为「绝不误删」付出的代价，比反过来要划算得多。
 */
@Entity(tableName = "trash")
data class TrashEntity(
    @PrimaryKey val mediaId: Long,
    val uri: String,
    val displayName: String,
    val kind: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val albumName: String,
    val deletedAtMillis: Long,
)

@Dao
interface TrashDao {

    @Query("SELECT * FROM trash ORDER BY deletedAtMillis DESC")
    fun observeAll(): Flow<List<TrashEntity>>

    @Query("SELECT mediaId FROM trash")
    suspend fun allMediaIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TrashEntity>)

    @Query("DELETE FROM trash WHERE mediaId IN (:mediaIds)")
    suspend fun deleteByMediaIds(mediaIds: List<Long>)

    @Query("DELETE FROM trash")
    suspend fun clear()
}

@Database(entities = [TrashEntity::class], version = 1, exportSchema = false)
abstract class TrashDatabase : RoomDatabase() {
    abstract fun trashDao(): TrashDao
}

fun MediaItem.toTrashEntity(nowMillis: Long): TrashEntity = TrashEntity(
    mediaId = id,
    uri = uri,
    displayName = displayName,
    kind = kind.name,
    sizeBytes = sizeBytes,
    dateMillis = effectiveDateMillis,
    durationMillis = durationMillis ?: 0L,
    width = width,
    height = height,
    albumName = albumName,
    deletedAtMillis = nowMillis,
)

fun TrashEntity.toMediaItem(): MediaItem = MediaItem(
    id = mediaId,
    uri = uri,
    displayName = displayName,
    // 存量数据里若出现无法识别的类型，退回图片而不是抛异常把回收站整页打挂
    kind = runCatching { MediaKind.valueOf(kind) }.getOrDefault(MediaKind.IMAGE),
    sizeBytes = sizeBytes,
    dateTakenMillis = dateMillis,
    dateAddedMillis = dateMillis,
    width = width,
    height = height,
    durationMillis = durationMillis.takeIf { it > 0L },
    albumId = 0L,
    albumName = albumName,
    relativePath = null,
)
