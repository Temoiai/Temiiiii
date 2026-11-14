package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "streamvault_prefs")

data class UserPreferences(
    val wifiOnly: Boolean = false,
    val autoSaveToGallery: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val AUTO_SAVE_GALLERY = booleanPreferencesKey("auto_save_gallery")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val themeStr = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        UserPreferences(
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            autoSaveToGallery = prefs[Keys.AUTO_SAVE_GALLERY] ?: true,
            themeMode = runCatching { ThemeMode.valueOf(themeStr) }.getOrDefault(ThemeMode.DARK)
        )
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setAutoSaveToGallery(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SAVE_GALLERY] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
}
