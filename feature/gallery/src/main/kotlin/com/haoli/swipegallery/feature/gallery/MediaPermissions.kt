package com.haoli.swipegallery.feature.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 读取媒体所需的运行时权限。
 *
 * - Android 13 (API 33) 起使用细粒度权限，图片与视频分开申请（系统只弹一次合并对话框）
 * - Android 12 及以下回退到 READ_EXTERNAL_STORAGE
 */
fun requiredMediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/**
 * 判断是否拿到了可用的媒体读取权限。
 *
 * Android 14 (API 34) 引入了「仅选择部分照片」——此时 READ_MEDIA_IMAGES 仍是拒绝态，
 * 但 READ_MEDIA_VISUAL_USER_SELECTED 是授予态。若只判断前者，会把「部分授权」
 * 误判成「完全没有权限」，然后反复弹申请框把用户惹烦。
 */
fun hasMediaPermission(context: Context): Boolean {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                )
    } else {
        granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/**
 * 是否处于「部分授权」状态。
 * 这种状态下只能看到用户选中的那几张，界面上需要给出明确提示，
 * 否则用户会以为 App 坏掉了。
 */
fun isPartialMediaAccess(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

    val selectedGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED

    val fullGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_IMAGES,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) == PackageManager.PERMISSION_GRANTED

    return selectedGranted && !fullGranted
}
