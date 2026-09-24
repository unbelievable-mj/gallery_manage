package com.haoli.swipegallery.core.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 缓存预算：取可用堆的 1/6，其余留给解码与 Compose 渲染。
 * 做成顶层函数而非伴生对象成员，避免子类构造期解析作用域的歧义。
 */
private fun defaultBitmapCacheBytes(): Int {
    val budget = Runtime.getRuntime().maxMemory() / 6
    return budget.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * 内存位图缓存的公共基类。
 *
 * 按字节数计量而非条目数 —— 一张 4000×3000 的位图和一张缩略图差了两个数量级，
 * 按条目数限制会让内存占用完全失控。
 */
abstract class BitmapLruCache(maxBytes: Int) {

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    protected fun get(key: String): Bitmap? = cache.get(key)

    protected fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() = cache.evictAll()
}

/**
 * 缩略图加载器，网格与滑卡预加载共用。
 *
 * 使用 [android.content.ContentResolver.loadThumbnail]，它对图片和视频都有效 ——
 * 视频会返回首帧，因此不需要为两种媒体各写一套逻辑。
 * 该 API 会自动应用 EXIF 旋转，缩略图方向无需额外处理。
 */
@Singleton
class ThumbnailLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) : BitmapLruCache(defaultBitmapCacheBytes()) {

    /**
     * 限制并发解码数。
     *
     * 网格快速滚动时会有几十个单元同时请求缩略图，不加限制会瞬间申请大量位图内存，
     * 低端机上直接 OOM。限流后多余的请求排队，用户体验几乎无感。
     */
    private val semaphore = Semaphore(permits = MAX_CONCURRENT_DECODES)

    suspend fun load(uri: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri@$sizePx"

        get(key) ?: semaphore.withPermit {
            // 等锁期间可能已被别的协程填好，这里必须二次检查，
            // 否则同一张图会被重复解码
            get(key) ?: runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(uri), Size(sizePx, sizePx), null)
            }.getOrNull()?.also { put(key, it) }
        }
    }

    private companion object {
        const val MAX_CONCURRENT_DECODES = 4
    }
}

/**
 * 全尺寸图片加载器，查看器使用。
 *
 * 按目标尺寸降采样后再解码，避免把 8000×6000 的原图整张读进内存。
 * 注意 `BitmapFactory.decodeStream` **不会**自动应用 EXIF 旋转，竖拍照片必须手工纠正，
 * 否则会横过来 —— 缩略图那条路径没这个问题，但全尺寸解码有。
 */
@Singleton
class FullImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) : BitmapLruCache(defaultBitmapCacheBytes()) {

    suspend fun load(uri: String, maxSizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$uri#full@$maxSizePx"
        get(key)?.let { return@withContext it }

        val bitmap = runCatching { decode(uri, maxSizePx) }.getOrNull()
            ?: return@withContext null

        put(key, bitmap)
        bitmap
    }

    private fun decode(uri: String, maxSizePx: Int): Bitmap? {
        val parsed = Uri.parse(uri)

        // 第一遍只读尺寸，不分配像素内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(width, height, maxSizePx)
        }

        val decoded = context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        return applyExifOrientation(parsed, decoded)
    }

    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }

        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private companion object {
        /** 逐步折半，直到最长边不超过目标尺寸。 */
        fun calculateInSampleSize(width: Int, height: Int, maxSize: Int): Int {
            var sample = 1
            while (width / sample > maxSize || height / sample > maxSize) {
                sample *= 2
            }
            return sample
        }
    }
}
