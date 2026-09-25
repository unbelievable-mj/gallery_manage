package com.haoli.swipegallery.core.data.di

import com.haoli.swipegallery.core.data.MediaRepository
import com.haoli.swipegallery.core.data.MediaStoreMediaRepository
import com.haoli.swipegallery.core.data.settings.DataStoreSettingsRepository
import com.haoli.swipegallery.core.data.settings.SettingsRepository
import com.haoli.swipegallery.core.data.usage.DataStoreUsageRepository
import com.haoli.swipegallery.core.data.usage.UsageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 注意：模块必须是 public。Hilt 生成的组件代码位于 app 模块，
 * 若这里声明为 internal，跨模块引用会编译失败。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaStoreMediaRepository): MediaRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindUsageRepository(impl: DataStoreUsageRepository): UsageRepository
}
