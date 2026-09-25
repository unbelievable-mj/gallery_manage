package com.haoli.swipegallery.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.haoli.swipegallery.core.model.AppSettings
import com.haoli.swipegallery.core.model.MoveTarget
import com.haoli.swipegallery.core.model.SortDirection
import com.haoli.swipegallery.core.model.SortField
import com.haoli.swipegallery.core.model.ThemeMode
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
    suspend fun setMoveTarget(target: MoveTarget?)
    suspend fun setDefaultSort(field: SortField, direction: SortDirection)
    suspend fun setDuplicateThreshold(bytes: Long)
    suspend fun setThemeMode(mode: ThemeMode)
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
            moveTarget = prefs.readMoveTarget(),
            defaultSortField = prefs[Keys.SORT_FIELD].toEnumOrNull<SortField>()
                ?: SortField.DATE_TAKEN,
            defaultSortDirection = prefs[Keys.SORT_DIRECTION].toEnumOrNull<SortDirection>()
                ?: SortDirection.DESC,
            duplicateThresholdBytes = prefs[Keys.DUPLICATE_THRESHOLD]
                ?.let(AppSettings::sanitizeDuplicateThreshold)
                ?: AppSettings.DEFAULT_DUPLICATE_THRESHOLD_BYTES,
            themeMode = prefs[Keys.THEME_MODE].toEnumOrNull<ThemeMode>()
                ?: ThemeMode.SYSTEM,
        )
    }

    /**
     * 三个键缺一不可。
     *
     * 只写成功一半（比如 id 与名字写进去了、路径没写）时返回 null，
     * 让下滑退回「保留在原相册」，而不是拿着空路径去移动文件。
     */
    private fun Preferences.readMoveTarget(): MoveTarget? {
        val id = this[Keys.MOVE_TARGET_ID] ?: return null
        val path = this[Keys.MOVE_TARGET_PATH].orEmpty()
        if (path.isBlank()) return null
        return MoveTarget(
            albumId = id,
            albumName = this[Keys.MOVE_TARGET_NAME].orEmpty().ifBlank { "目标相册" },
            relativePath = path,
        )
    }

    override suspend fun setPreloadCount(count: Int) {
        val safe = AppSettings.sanitizePreloadCount(count)
        context.settingsDataStore.edit { it[Keys.PRELOAD_COUNT] = safe }
    }

    override suspend fun setMoveTarget(target: MoveTarget?) {
        context.settingsDataStore.edit { prefs ->
            if (target == null) {
                prefs.remove(Keys.MOVE_TARGET_ID)
                prefs.remove(Keys.MOVE_TARGET_NAME)
                prefs.remove(Keys.MOVE_TARGET_PATH)
            } else {
                prefs[Keys.MOVE_TARGET_ID] = target.albumId
                prefs[Keys.MOVE_TARGET_NAME] = target.albumName
                prefs[Keys.MOVE_TARGET_PATH] = target.relativePath
            }
        }
    }

    override suspend fun setDefaultSort(field: SortField, direction: SortDirection) {
        context.settingsDataStore.edit {
            it[Keys.SORT_FIELD] = field.name
            it[Keys.SORT_DIRECTION] = direction.name
        }
    }

    override suspend fun setDuplicateThreshold(bytes: Long) {
        val safe = AppSettings.sanitizeDuplicateThreshold(bytes)
        context.settingsDataStore.edit { it[Keys.DUPLICATE_THRESHOLD] = safe }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
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
        val MOVE_TARGET_ID = longPreferencesKey("move_target_id")
        val MOVE_TARGET_NAME = stringPreferencesKey("move_target_name")
        val MOVE_TARGET_PATH = stringPreferencesKey("move_target_path")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_DIRECTION = stringPreferencesKey("sort_direction")
        val DUPLICATE_THRESHOLD = longPreferencesKey("duplicate_threshold")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
