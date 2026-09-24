package com.haoli.swipegallery.feature.gallery

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
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

/**
 * 是否已获得「媒体管理」特殊权限。
 *
 * 拿到它之后 `createDeleteRequest` 等调用不再逐次弹系统确认框。
 *
 * **当前界面没有使用它**：本项目的「彻底删除」是不可逆操作，
 * 我们**正需要**那个系统确认框。保留这两个函数是为了将来若加入
 * 「删除免确认」的可选模式时可以直接接上。
 * 官方要求应用以 API 31+ 为目标平台才有资格申请，本项目 targetSdk 37 满足。
 *
 * 判定优先用 AppOps：`checkSelfPermission` 对「特殊应用权限」不一定可靠，
 * 可能在实际已授权时仍返回 DENIED，导致界面一直提示去授权。
 */
fun hasManageMediaPermission(context: Context): Boolean {
    val viaAppOps = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            MANAGE_MEDIA_OP,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    return viaAppOps ?: (
        ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_MEDIA) ==
            PackageManager.PERMISSION_GRANTED
        )
}

/**
 * 跳转到「媒体管理应用」特殊权限页。
 *
 * 用字符串常量而非 `Settings.ACTION_REQUEST_MANAGE_MEDIA`：
 * 后者在部分 SDK 里常量名不确定，写错会直接编译失败。
 * 这里按优先级逐个尝试，失败就降级到应用详情页 —— 用户在权限列表里同样能找到它。
 */
fun openManageMediaSettings(context: Context) {
    for (action in MANAGE_MEDIA_SETTINGS_ACTIONS) {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

private const val MANAGE_MEDIA_OP = "android:manage_media"

private val MANAGE_MEDIA_SETTINGS_ACTIONS = listOf(
    "android.settings.REQUEST_MANAGE_MEDIA",
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
)
