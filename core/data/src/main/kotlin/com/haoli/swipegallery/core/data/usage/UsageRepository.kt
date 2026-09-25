package com.haoli.swipegallery.core.data.usage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 使用统计的持久化。
 *
 * 与「设置」分开存：设置是用户做的选择，这里是应用跑出来的累计值。
 * 混在一个文件里语义不清，将来想清空统计也会误伤设置。
 */
interface UsageRepository {
    /** 累计通过永久删除释放的字节数。 */
    val freedBytes: Flow<Long>

    /**
     * 累加一次释放量。
     *
     * 传 0 或负数会被忽略 —— 累计值只增不减，负数会把它算坏。
     */
    suspend fun addFreed(bytes: Long)
}

/** 与设置分开的文件，见 [UsageRepository] 的注释。 */
private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "swipegallery_usage",
)

@Singleton
class DataStoreUsageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsageRepository {

    override val freedBytes: Flow<Long> = context.usageDataStore.data.map { prefs ->
        prefs[Keys.FREED_BYTES] ?: 0L
    }

    override suspend fun addFreed(bytes: Long) {
        if (bytes <= 0L) return

        context.usageDataStore.edit { prefs ->
            // 读出来再加：这是「累计」值，每次删除都要叠加，不能覆盖
            prefs[Keys.FREED_BYTES] = (prefs[Keys.FREED_BYTES] ?: 0L) + bytes
        }
    }

    private object Keys {
        val FREED_BYTES = longPreferencesKey("freed_bytes")
    }
}
