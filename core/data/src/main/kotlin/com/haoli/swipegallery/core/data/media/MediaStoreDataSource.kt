package com.haoli.swipegallery.core.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.MediaKind
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import com.haoli.swipegallery.core.model.SortSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore 查询层。
 *
 * 只负责「读」，不做任何写操作 —— 所有删除/修改必须走 [MediaOperator]。
 *
 * 列名使用类型专属常量（`MediaStore.Images.Media.*` / `MediaStore.Video.Media.*`）而非
 * `MediaStore.MediaColumns.*`：`DATE_TAKEN` 与 `DURATION` 并不在 `MediaColumns` 上，
 * 图片与视频的列集也不完全一致。
 */
@Singleton
class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver get() = context.contentResolver

    fun items(
        kind: MediaKind,
        spec: SortSpec,
        albumId: Long? = null,
        trashed: Boolean = false,
    ): List<MediaItem> {
        val result = ArrayList<MediaItem>(256)

        resolver.query(
            collectionFor(kind),
            projectionFor(kind),
            selectionFor(kind, albumId, trashed),
            albumId?.let { arrayOf(it.toString()) },
            sortOrderFor(spec, kind),
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val addedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val takenIdx = cursor.getColumnIndex(dateTakenColumn(kind))
            val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
            val durationIdx = if (kind == MediaKind.VIDEO) {
                cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            } else {
                -1
            }
            val bucketIdIdx = cursor.getColumnIndex(bucketIdColumn(kind))
            val bucketNameIdx = cursor.getColumnIndex(bucketNameColumn(kind))
            val relativePathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val dateAddedSeconds = if (addedIdx >= 0) cursor.getLong(addedIdx) else 0L
                val dateTakenSeconds = if (takenIdx >= 0) cursor.getLong(takenIdx) else 0L

                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collectionFor(kind), id).toString(),
                    displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "",
                    kind = kind,
                    // 夹到非负：格式化函数对负数会抛异常，而 MediaStore 在某些异常状态下
                    // 确实可能返回负值，不该让展示层为此崩溃
                    sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L,
                    // MediaStore 用秒，统一转成毫秒；0 视为缺失
                    dateTakenMillis = dateTakenSeconds.takeIf { it > 0L }?.times(1000L),
                    dateAddedMillis = dateAddedSeconds * 1000L,
                    width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0,
                    height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0,
                    durationMillis = if (durationIdx >= 0) {
                        cursor.getLong(durationIdx).coerceAtLeast(0L)
                    } else {
                        null
                    },
                    albumId = if (bucketIdIdx >= 0) cursor.getLong(bucketIdIdx) else 0L,
                    albumName = if (bucketNameIdx >= 0) cursor.getString(bucketNameIdx).orEmpty() else "",
                    relativePath = if (relativePathIdx >= 0) cursor.getString(relativePathIdx) else null,
                )
            }
        }

        return result
    }

    fun snapshot(): LibrarySnapshot {
        val (imageCount, imageBytes) = countAndSize(MediaKind.IMAGE)
        val (videoCount, videoBytes) = countAndSize(MediaKind.VIDEO)

        return LibrarySnapshot(
            imageCount = imageCount,
            videoCount = videoCount,
            totalSizeBytes = imageBytes + videoBytes,
            isStub = false,
        )
    }

    /**
     * 列出某个类型下的全部相册及其数量。
     *
     * MediaStore 不支持通过 ContentResolver 做 GROUP BY，只能把 bucket 列拉回来
     * 在内存里聚合。行数等于媒体总数（万级也就几十毫秒），比逐相册发一次查询快得多。
     */
    fun albums(kind: MediaKind): List<MediaAlbum> {
        val counts = LinkedHashMap<Long, Pair<String, Int>>()
        val idColumn = bucketIdColumn(kind)
        val nameColumn = bucketNameColumn(kind)

        resolver.query(
            collectionFor(kind),
            arrayOf(idColumn, nameColumn),
            ACTIVE_SELECTION,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(idColumn)
            val nameIdx = cursor.getColumnIndex(nameColumn)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val previous = counts[id]
                counts[id] = (previous?.first ?: name) to ((previous?.second ?: 0) + 1)
            }
        }

        return counts
            .map { (id, value) ->
                MediaAlbum(
                    id = id,
                    // 部分来源的 bucket 名为空，给个兜底文案，否则列表里会出现空白项
                    name = value.first.ifBlank { "未命名相册" },
                    itemCount = value.second,
                    kind = kind,
                )
            }
            .sortedWith(compareByDescending<MediaAlbum> { it.itemCount }.thenBy { it.name })
    }

    private fun countAndSize(kind: MediaKind): Pair<Int, Long> {
        var count = 0
        var bytes = 0L

        resolver.query(
            collectionFor(kind),
            arrayOf(MediaStore.MediaColumns.SIZE),
            ACTIVE_SELECTION,
            null,
            null,
        )?.use { cursor ->
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            while (cursor.moveToNext()) {
                count++
                if (sizeIdx >= 0) bytes += cursor.getLong(sizeIdx)
            }
        }

        return count to bytes
    }

    /** 图片与视频分别取各自的媒体集合。 */
    fun collectionFor(kind: MediaKind): Uri = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }

    private fun projectionFor(kind: MediaKind): Array<String> = when (kind) {
        MediaKind.IMAGE -> arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )

        MediaKind.VIDEO -> arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
        )
    }

    private fun dateTakenColumn(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.DATE_TAKEN
        MediaKind.VIDEO -> MediaStore.Video.Media.DATE_TAKEN
    }

    private fun bucketIdColumn(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.BUCKET_ID
        MediaKind.VIDEO -> MediaStore.Video.Media.BUCKET_ID
    }

    private fun bucketNameColumn(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        MediaKind.VIDEO -> MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    }

    /**
     * 图片没有 DURATION 列，若用户给图片选了按时长排序，回退到拍摄时间，
     * 否则 MediaStore 会直接抛异常。
     */
    private fun sortOrderFor(spec: SortSpec, kind: MediaKind): String {
        val field = if (kind == MediaKind.IMAGE && spec.field == SortField.DURATION) {
            SortField.DATE_TAKEN
        } else {
            spec.field
        }

        val column = when (field) {
            SortField.DATE_TAKEN -> dateTakenColumn(kind)
            SortField.DATE_MODIFIED -> MediaStore.MediaColumns.DATE_MODIFIED
            SortField.SIZE -> MediaStore.MediaColumns.SIZE
            SortField.DISPLAY_NAME -> MediaStore.MediaColumns.DISPLAY_NAME
            SortField.DURATION -> MediaStore.Video.Media.DURATION
        }

        val direction = if (spec.direction == SortDirection.ASC) "ASC" else "DESC"

        // 次级排序用 ID 保证顺序稳定，避免同值时列表跳动
        return "$column $direction, ${MediaStore.MediaColumns._ID} DESC"
    }

    /**
     * 系统回收站里的项目。
     *
     * 被移入回收站的文件**不会**出现在常规查询里 —— 这正是「删了之后在相册里找不到、
     * 以为被永久删除」的原因。它们其实还在磁盘上，只是 IS_TRASHED 被置为 1，
     * 系统会在保留期结束后才真正清理。
     *
     * 这里把图片与视频合起来按时间倒序返回，让用户能确认内容还在、并且可以取回。
     */
    fun trashedItems(): List<MediaItem> {
        val spec = SortSpec(SortField.DATE_MODIFIED, SortDirection.DESC)
        return (
            items(kind = MediaKind.IMAGE, spec = spec, trashed = true) +
                items(kind = MediaKind.VIDEO, spec = spec, trashed = true)
            ).sortedByDescending { it.effectiveDateMillis }
    }

    /**
     * 基础筛选 + 可选的相册限制。
     * 相册用参数占位符而非字符串拼接，避免 bucket 名里出现引号时把 SQL 拼坏。
     */
    private fun selectionFor(kind: MediaKind, albumId: Long?, trashed: Boolean): String {
        val base = if (trashed) TRASHED_SELECTION else ACTIVE_SELECTION
        return if (albumId == null) base else "$base AND ${bucketIdColumn(kind)} = ?"
    }

    private companion object {
        /** 正常可见的媒体：未进回收站、且写入已完成。 */
        const val ACTIVE_SELECTION =
            "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"

        /** 系统回收站中的媒体。 */
        const val TRASHED_SELECTION = "${MediaStore.MediaColumns.IS_TRASHED} = 1"
    }
}
