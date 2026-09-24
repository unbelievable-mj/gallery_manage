package com.haoli.swipegallery.core.data.di

import android.content.Context
import androidx.room.Room
import com.haoli.swipegallery.core.data.trash.TrashDao
import com.haoli.swipegallery.core.data.trash.TrashDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideTrashDatabase(@ApplicationContext context: Context): TrashDatabase =
        Room.databaseBuilder(
            context,
            TrashDatabase::class.java,
            "swipegallery.db",
        ).build()

    @Provides
    fun provideTrashDao(database: TrashDatabase): TrashDao = database.trashDao()
}
