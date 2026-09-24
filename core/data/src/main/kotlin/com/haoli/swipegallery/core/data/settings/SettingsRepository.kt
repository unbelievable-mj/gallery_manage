package com.haoli.swipegallery.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.KeepTarget
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 设置项的持久化。
 *
 * 用 DataStore Preferences 而非 SharedPreferences：前者是协程友好且事务安全的，
 * 后者在跨进程与并发写入时会出现丢失更新的经典问题。
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setPreloadCount(count: Int)
    suspend fun setKeepTarget(target: KeepTarget)
    suspend fun setDefaultSort(field: SortField, direction: SortDirection)
}

/** 必须声明为 Context 的顶层扩展，DataStore 要求同一文件只创建一次实例。 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "swipegallery_settings",
)

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            preloadCount = prefs[Keys.PRELOAD_COUNT]
                ?.let(AppSettings::sanitizePreloadCount)
                ?: AppSettings.DEFAULT_PRELOAD_COUNT,
            keepTarget = prefs[Keys.KEEP_TARGET].toEnumOrNull<KeepTarget>()
                ?: KeepTarget.ORIGINAL_ALBUM,
            defaultSortField = prefs[Keys.SORT_FIELD].toEnumOrNull<SortField>()
                ?: SortField.DATE_TAKEN,
            defaultSortDirection = prefs[Keys.SORT_DIRECTION].toEnumOrNull<SortDirection>()
                ?: SortDirection.DESC,
        )
    }

    override suspend fun setPreloadCount(count: Int) {
        val safe = AppSettings.sanitizePreloadCount(count)
        context.settingsDataStore.edit { it[Keys.PRELOAD_COUNT] = safe }
    }

    override suspend fun setKeepTarget(target: KeepTarget) {
        context.settingsDataStore.edit { it[Keys.KEEP_TARGET] = target.name }
    }

    override suspend fun setDefaultSort(field: SortField, direction: SortDirection) {
        context.settingsDataStore.edit {
            it[Keys.SORT_FIELD] = field.name
            it[Keys.SORT_DIRECTION] = direction.name
        }
    }

    /**
     * 枚举名解析失败时返回 null 而不是抛异常。
     *
     * 枚举成员一旦改名，旧版本写进 DataStore 的字符串就会失效；
     * 这时应当退回默认值，而不是让整个设置页崩掉。
     */
    private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
        this?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }

    private object Keys {
        val PRELOAD_COUNT = intPreferencesKey("preload_count")
        val KEEP_TARGET = stringPreferencesKey("keep_target")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_DIRECTION = stringPreferencesKey("sort_direction")
    }
}
