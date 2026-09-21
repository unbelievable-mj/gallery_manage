package com.haoli.swipegallery.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FormattersTest {

    @Test
    fun `formatFileSize 在 1024 字节以下直接显示字节`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun `formatFileSize 在 KB 区间保留一位小数`() {
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 KB", formatFileSize(1536))
    }

    @Test
    fun `formatFileSize 在 MB 区间保留一位小数`() {
        assertEquals("1.0 MB", formatFileSize(1024L * 1024))
        assertEquals("5.0 MB", formatFileSize(5L * 1024 * 1024))
    }

    @Test
    fun `formatFileSize 在 GB 区间保留两位小数`() {
        assertEquals("1.00 GB", formatFileSize(1024L * 1024 * 1024))
        assertEquals("5.00 GB", formatFileSize(5L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatFileSize 拒绝负数`() {
        assertThrows(IllegalArgumentException::class.java) {
            formatFileSize(-1)
        }
    }

    @Test
    fun `formatDuration 一小时以内显示 mm colon ss`() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:05", formatDuration(5_000))
        assertEquals("01:05", formatDuration(65_000))
    }

    @Test
    fun `formatDuration 超过一小时显示 h colon mm colon ss`() {
        assertEquals("1:00:00", formatDuration(3_600_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun `formatDuration 拒绝负数`() {
        assertThrows(IllegalArgumentException::class.java) {
            formatDuration(-1)
        }
    }
}
