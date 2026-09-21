package com.haoli.swipegallery

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * 应用入口。
 *
 * [HiltAndroidApp] 会触发 Hilt 的代码生成，这是验证
 * 「AGP 9 内置 Kotlin + KSP + Hilt」这条工具链是否打通的第一个关卡。
 */
@HiltAndroidApp
class SwipeGalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
