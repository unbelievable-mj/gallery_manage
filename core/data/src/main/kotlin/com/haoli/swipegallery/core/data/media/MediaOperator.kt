package com.haoli.swipegallery.core.data.media

import android.content.ContentResolver
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
 * 所有删除/保留/移动都必须经过这里 —— 它是撤销能力能够成立的唯一前提。
 *
 * 删除采用两阶段提交（见 DEVELOPMENT_PLAN §3.5）：
 *  - 阶段一：调用方在本地维护待删队列并从 UI 移除，不触碰系统（零延迟、完全可逆）
 *  - 阶段二：由这里批量构造系统回收站请求，一次性交给用户确认
 *
 * 之所以不逐个删除：每次 `createTrashRequest` 都会弹一次系统对话框，
 * 逐张弹会彻底打断滑卡节奏。
 */
@Singleton
class MediaOperator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 构造「移入系统回收站」请求（阶段二）。
     *
     * 返回 null 表示没有可处理的目标。返回值需要交给
     * `ActivityResultContracts.StartIntentSenderForResult` 启动，
     * 系统会弹出一次确认对话框，用户同意后才真正执行。
     */
    fun trashRequest(uris: List<String>): IntentSender? = request(uris) { resolver, targets ->
        MediaStore.createTrashRequest(resolver, targets, true)
    }

    /**
     * 构造「从系统回收站还原」请求。
     *
     * 用于撤销窗口过期之后的反悔 —— 此时文件已进入系统回收站，
     * 只能再发一次请求把它取回来。
     */
    fun untrashRequest(uris: List<String>): IntentSender? = request(uris) { resolver, targets ->
        MediaStore.createTrashRequest(resolver, targets, false)
    }

    /**
     * 构造「永久删除」请求。跳过回收站，不可恢复。
     * 只应在回收站页面用户明确选择「彻底删除」时使用。
     */
    fun deleteRequest(uris: List<String>): IntentSender? = request(uris) { resolver, targets ->
        MediaStore.createDeleteRequest(resolver, targets)
    }

    private inline fun request(
        uris: List<String>,
        build: (ContentResolver, Collection<Uri>) -> android.app.PendingIntent,
    ): IntentSender? {
        if (uris.isEmpty()) return null

        return runCatching {
            build(resolver, uris.map(Uri::parse)).intentSender
        }.onFailure {
            // 常见原因：URI 不属于 MediaStore、数量超限、或系统回收站不可用
            Timber.w(it, "构造媒体写请求失败，目标数量=%d", uris.size)
        }.getOrNull()
    }
}
