package com.haoli.swipegallery.core.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * 媒体写操作的唯一出口。
 *
 * **项目铁律**：UI 层与 ViewModel 永远不直接调用 `ContentResolver.delete()`。
 * 所有删除都必须经过这里 —— 它是撤销能力能够成立的唯一前提。
 *
 * 删除分两步，这个区分很关键：
 *  - **滑卡时**：只把内容记进应用自己的回收站（`TrashDao`），**绝不碰系统**。
 *    系统接口在各类 ROM 上的表现并不一致（是否真的进回收站、回收站入口是否可见，
 *    都因厂商而异），而滑卡没有二次确认，一旦行为不符预期就是静默的数据丢失，
 *    用户没有任何补救手段。
 *  - **用户在回收站里点「删除」时**：才调用 [purgeRequest]。
 *    此时已有明确确认，即便退化也是用户想要的结果。
 */
@Singleton
class MediaOperator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 构造「永久删除」请求。**这是全应用唯一会真正销毁文件的地方。**
     *
     * 必须用 `createDeleteRequest` 而不是 `createTrashRequest`：
     * 后者只是把文件移进系统回收站，**磁盘占用一点都不会变**。
     * 用户点「删除」的意图是释放空间，把它塞进另一个回收站等于没删。
     *
     * 曾经这里写的是「先试 createTrashRequest，失败才退回永久删除」，
     * 结果是用户清空了几百兆却发现空间毫无变化 —— 因为在支持系统回收站的
     * 设备上，第一个分支总是成功，永久删除永远不会被执行。
     */
    fun purgeRequest(uris: List<String>): IntentSender? {
        if (uris.isEmpty()) return null

        return runCatching {
            MediaStore.createDeleteRequest(resolver, uris.map(Uri::parse)).intentSender
        }.onFailure {
            // 常见原因：URI 不属于 MediaStore、数量超限
            Timber.w(it, "构造删除请求失败，目标数量=%d", uris.size)
        }.getOrNull()
    }

    /**
     * 从系统回收站取回。
     *
     * `createTrashRequest` 的第三个参数控制方向：true 进回收站，false 取回。
     */
    fun untrashRequest(uris: List<String>): IntentSender? {
        if (uris.isEmpty()) return null

        return runCatching {
            MediaStore.createTrashRequest(resolver, uris.map(Uri::parse), false).intentSender
        }.onFailure {
            Timber.w(it, "构造还原请求失败，目标数量=%d", uris.size)
        }.getOrNull()
    }

    /**
     * 构造「写入授权」请求。
     *
     * 移动文件需要先拿到系统授予的写权限。用 `createWriteRequest` 一次为整批文件申请，
     * 用户点一次即可 —— 逐个申请会弹到人烦。
     */
    fun writeRequest(uris: List<String>): IntentSender? {
        if (uris.isEmpty()) return null

        return runCatching {
            MediaStore.createWriteRequest(resolver, uris.map(Uri::parse)).intentSender
        }.onFailure {
            Timber.w(it, "构造写入授权请求失败，目标数量=%d", uris.size)
        }.getOrNull()
    }

    /**
     * 把文件移动到目标目录。
     *
     * 移动的本质是改写文件的 `RELATIVE_PATH` —— 文件内容不动，
     * 只是归属的目录变了，因此不产生额外的磁盘占用，也不会有复制中断丢数据的风险。
     *
     * **必须在 [writeRequest] 拿到用户同意之后调用**，否则 `update` 会因缺少写权限失败。
     * 返回成功移动的条数，失败的单条不影响其余项。
     */
    fun moveToAlbum(uris: List<String>, targetRelativePath: String): Int {
        if (uris.isEmpty() || targetRelativePath.isBlank()) return 0

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelativePath)
        }

        return uris.count { raw ->
            runCatching {
                resolver.update(Uri.parse(raw), values, null, null) > 0
            }.getOrElse {
                Timber.w(it, "移动失败: %s", raw)
                false
            }
        }
    }
}
