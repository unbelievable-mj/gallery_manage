package com.haoli.swipegallery.core.model

/**
 * 一组疑似重复的文件。
 *
 * 「疑似」是认真的：判据只有文件大小接近，不做内容比对。
 * 连拍、同一场景的不同曝光都可能被归到一组，所以界面必须让用户自己看缩略图判断，
 * 绝不能提供「一键全部删除」这种按钮。
 */
data class DuplicateGroup(
    val items: List<MediaItem>,
) {
    val count: Int get() = items.size

    /** 组内最小的那份，通常是最该保留的。 */
    val smallestBytes: Long get() = items.minOf { it.sizeBytes }

    val largestBytes: Long get() = items.maxOf { it.sizeBytes }

    /** 每组只留一份的话能释放多少空间。 */
    val reclaimableBytes: Long get() = items.sumOf { it.sizeBytes } - smallestBytes
}

/** 一次查重的结果。 */
data class DuplicateScan(
    val groups: List<DuplicateGroup> = emptyList(),
    /** 参与比对的文件总数，用于说明「在多大的范围里查的」。 */
    val scannedCount: Int = 0,
    val thresholdBytes: Long = 0L,
) {
    val groupCount: Int get() = groups.size
    val fileCount: Int get() = groups.sumOf { it.count }
    val reclaimableBytes: Long get() = groups.sumOf { it.reclaimableBytes }
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * 按文件大小接近程度分组，找出疑似重复。
 *
 * 判据是「组内最大与最小相差不超过 [thresholdBytes]」，即与**组内最小值**比较。
 * 不能改成与「相邻项」比较：那样一组会不断接力扩大 ——
 * 100、115、130、145 相邻都只差 15，一路串下去会把 100 和 145 归到一起，
 * 组的边界完全失去意义。
 *
 * 组内按大小升序，方便界面把「保留最小的那份」摆在最前面。
 */
fun List<MediaItem>.groupSuspectedDuplicates(thresholdBytes: Long): List<DuplicateGroup> {
    if (size < 2) return emptyList()
    val threshold = thresholdBytes.coerceAtLeast(0L)

    val sorted = sortedBy { it.sizeBytes }
    val groups = mutableListOf<DuplicateGroup>()
    var bucket = mutableListOf<MediaItem>()

    for (item in sorted) {
        val smallest = bucket.firstOrNull()?.sizeBytes
        if (smallest != null && item.sizeBytes - smallest > threshold) {
            if (bucket.size >= 2) groups += DuplicateGroup(bucket.toList())
            bucket = mutableListOf()
        }
        bucket += item
    }
    if (bucket.size >= 2) groups += DuplicateGroup(bucket.toList())

    // 能省得多的排前面，用户先看收益最大的
    return groups.sortedByDescending { it.reclaimableBytes }
}
