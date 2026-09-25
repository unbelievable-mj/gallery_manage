package com.haoli.swipegallery.core.data.media

import android.content.ContentUris
import android.database.Cursor
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.haoli.swipegallery.core.model.LibrarySnapshot
import com.haoli.swipegallery.core.model.MediaAlbum
import com.haoli.swipegallery.core.model.MediaItem
import com.haoli.swipegallery.core.model.DateRange
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
        dateRange: DateRange? = null,
    ): List<MediaItem> {
        val (selection, args) = buildSelection(kind, albumId, dateRange)
        val result = ArrayList<MediaItem>(256)

        resolver.query(
            collectionFor(kind),
            projectionFor(kind),
            selection,
            args,
            sortOrderFor(spec, kind),
        )?.use { cursor -> readRows(cursor, kind, result) }

        return result
    }

    /**
     * 查询系统回收站里的内容（图片 + 视频，按时间倒序）。
     *
     * **必须走 query-arg，不能只写 SQL 条件。**
     * MediaStore 默认把 `IS_TRASHED = 1` 的行从所有操作里过滤掉 ——
     * 光写 `IS_TRASHED = 1` 只会得到空结果，AOSP 源码里写得很明确：
     * 「By default, trashed items are filtered away from operations.」
     *
     * 这正是「删掉的内容在回收站里看不到」的真正原因，
     * 而且我当初还据此误判成「该设备不支持系统回收站」，白改了一版架构。
     */
    fun trashedItems(): List<MediaItem> {
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }

        val result = ArrayList<MediaItem>(64)
        for (kind in listOf(MediaKind.IMAGE, MediaKind.VIDEO)) {
            resolver.query(
                collectionFor(kind),
                projectionFor(kind),
                queryArgs,
                null,
            )?.use { cursor -> readRows(cursor, kind, result) }
        }

        // 回收站的排序在内存里做：Bundle 那套查询参数不接 sortOrder 字符串
        return result.sortedByDescending { it.effectiveDateMillis }
    }

    /**
     * 逐行读取。列下标在循环外算一次 ——
     * 放在循环里对每一行重复 getColumnIndex 是明显的浪费。
     */
    private fun readRows(cursor: Cursor, kind: MediaKind, into: MutableList<MediaItem>) {
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

            into += MediaItem(
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
     * 列出某个类型下的全部相册及其数量与占用。
     *
     * MediaStore 不支持通过 ContentResolver 做 GROUP BY，只能把 bucket 与 size 列
     * 拉回来在内存里聚合。行数等于媒体总数（万级也就几十毫秒），比逐相册发查询快得多。
     */
    fun albums(kind: MediaKind): List<MediaAlbum> {
        val idColumn = bucketIdColumn(kind)
        val nameColumn = bucketNameColumn(kind)
        val acc = LinkedHashMap<Long, AlbumAccumulator>()

        resolver.query(
            collectionFor(kind),
            arrayOf(
                idColumn,
                nameColumn,
                MediaStore.MediaColumns.SIZE,
                // RELATIVE_PATH 是文件的目录，同一个 bucket 里都一样，取第一条即可
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            ACTIVE_SELECTION,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(idColumn)
            val nameIdx = cursor.getColumnIndex(nameColumn)
            val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val pathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                val path = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""

                val previous = acc[id]
                acc[id] = AlbumAccumulator(
                    name = previous?.name ?: name,
                    itemCount = (previous?.itemCount ?: 0) + 1,
                    bytes = (previous?.bytes ?: 0L) + size,
                    // 优先保留已经拿到的非空路径，避免个别行路径为空时把它覆盖掉
                    relativePath = previous?.relativePath?.takeIf { it.isNotEmpty() } ?: path,
                )
            }
        }

        return acc
            .map { (id, value) ->
                MediaAlbum(
                    id = id,
                    // 部分来源的 bucket 名为空，给个兜底文案，否则列表里会出现空白项
                    name = value.name.ifBlank { "未命名相册" },
                    itemCount = value.itemCount,
                    kind = kind,
                    totalBytes = value.bytes,
                    relativePath = value.relativePath,
                )
            }
            .sortedWith(compareByDescending<MediaAlbum> { it.itemCount }.thenBy { it.name })
    }

    /** 相册聚合过程中的中间结构，避免用四元组把代码读糊。 */
    private data class AlbumAccumulator(
        val name: String,
        val itemCount: Int,
        val bytes: Long,
        val relativePath: String,
    )

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
            SortField.DURATION -> MediaStore.Video.Media.DURATION
        }

        val direction = if (spec.direction == SortDirection.ASC) "ASC" else "DESC"

        // 次级排序用 ID 保证顺序稳定，避免同值时列表跳动
        return "$column $direction, ${MediaStore.MediaColumns._ID} DESC"
    }


    /**
     * 拼装筛选条件与参数。
     *
     * 全部用参数占位符而非字符串拼接 —— 相册名与搜索词都可能带引号，
     * 拼进去会把 SQL 弄坏，甚至变成注入点。
     *
     * 搜索不做 LIKE 通配符转义：文件名里 `_` 很常见（`IMG_1234.jpg`），
     * 转义反而让「搜 IMG_1234 搜不到」变得费解；而 `_` 作为通配符
     * 多匹配几个字符在实际使用中无害。
     */
    private fun buildSelection(
        kind: MediaKind,
        albumId: Long?,
        dateRange: DateRange?,
    ): Pair<String, Array<String>?> {
        val clauses = mutableListOf(ACTIVE_SELECTION)
        val args = mutableListOf<String>()

        if (albumId != null) {
            clauses += "${bucketIdColumn(kind)} = ?"
            args += albumId.toString()
        }

        if (dateRange != null) {
            // 与 MediaItem.effectiveDateMillis 的口径保持一致：
            // DATE_TAKEN 缺失或为 0 时回退到 DATE_ADDED。
            // 两者单位不同（DATE_TAKEN 是毫秒，DATE_ADDED 是秒），所以要乘 1000 对齐，
            // 否则回退分支会比实际早 1000 倍，筛选结果完全错位。
            clauses += "COALESCE(NULLIF(${dateTakenColumn(kind)}, 0), " +
                "${MediaStore.MediaColumns.DATE_ADDED} * 1000) BETWEEN ? AND ?"
            args += dateRange.startMillis.toString()
            args += dateRange.endMillis.toString()
        }

        return clauses.joinToString(" AND ") to args.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private companion object {
        /** 正常可见的媒体：未进回收站、且写入已完成。 */
        const val ACTIVE_SELECTION =
            "${MediaStore.MediaColumns.IS_TRASHED} = 0 AND ${MediaStore.MediaColumns.IS_PENDING} = 0"

        /** 系统回收站中的媒体。 */
    }
}
