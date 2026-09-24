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
 * 所有删除都必须经过这里 —— 它是撤销能力能够成立的唯一前提。
 *
 * 删除采用两阶段提交（见 DEVELOPMENT_PLAN §3.5）：
 *  - 阶段一：调用方把内容记进**应用自己的回收站**（`TrashDao`），文件原封不动
 *  - 阶段二：用户在回收站里明确点「彻底删除」时，由这里构造系统删除请求
 *
 * 为什么不把「删除」直接映射到系统的 `createTrashRequest`：
 * 该 API 在文档上确实是把文件移入系统回收站，但实测在部分 ROM 上并不生效，
 * 内容会直接消失且无法找回。把用户数据交给一个行为不可控的系统调用是错误的设计，
 * 所以这里**只保留不可逆的 `createDeleteRequest`**，且只在用户二次确认后调用。
 */
@Singleton
class MediaOperator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 构造「永久删除」请求。跳过回收站，不可恢复。
     *
     * 返回 null 表示没有可处理的目标。返回值需要交给
     * `ActivityResultContracts.StartIntentSenderForResult` 启动，
     * 系统会弹一次确认对话框 —— 这正是我们想要的，不可逆操作必须有明确确认。
     */
    fun deleteRequest(uris: List<String>): IntentSender? {
        if (uris.isEmpty()) return null

        return runCatching {
            MediaStore.createDeleteRequest(resolver, uris.map(Uri::parse)).intentSender
        }.onFailure {
            // 常见原因：URI 不属于 MediaStore、数量超限
            Timber.w(it, "构造媒体删除请求失败，目标数量=%d", uris.size)
        }.getOrNull()
    }
}
