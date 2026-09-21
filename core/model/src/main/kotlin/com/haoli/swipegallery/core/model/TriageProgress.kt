package com.haoli.swipegallery.core.model

/** 滑卡处理进度，用于查看器顶部的进度显示。 */
data class TriageProgress(
    val processed: Int,
    val total: Int,
    val deleted: Int,
    val kept: Int,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)

    companion object {
        fun of(total: Int, deleted: Int, kept: Int): TriageProgress = TriageProgress(
            processed = deleted + kept,
            total = total,
            deleted = deleted,
            kept = kept,
        )
    }
}
