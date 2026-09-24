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
 * 删除分两步，这个区分很关键：
 *  - **滑卡时**：只把内容记进应用自己的回收站（`TrashDao`），**绝不碰系统**。
 *    `createTrashRequest` 在部分 ROM 上会退化成真正删除，
 *    而滑卡这个动作没有二次确认，一旦退化就是静默的数据丢失。
 *  - **用户在回收站里点「删除」时**：才调用 [purgeRequest]。
 *    此时已有明确确认，即便退化也是用户想要的结果。
 */
@Singleton
class MediaOperator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * 构造删除请求：**优先移入系统回收站，设备不支持时退回永久删除**。
     *
     * 这里可以安全使用 `createTrashRequest`，因为调用点已经拿到用户的明确确认
     * （回收站页面的按钮 + 紧随其后的系统对话框）。两种结果都符合用户意图：
     *  - 设备支持回收站 → 内容进系统回收站，之后在手机图库里也能找回
     *  - 设备不支持 → 退回永久删除，就是用户点的那个「删除」
     */
    fun purgeRequest(uris: List<String>): IntentSender? {
        if (uris.isEmpty()) return null
        val targets = uris.map(Uri::parse)

        return runCatching {
            MediaStore.createTrashRequest(resolver, targets, true).intentSender
        }.recoverCatching {
            // 部分设备或存储卷不支持回收站，退回永久删除
            MediaStore.createDeleteRequest(resolver, targets).intentSender
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
}
