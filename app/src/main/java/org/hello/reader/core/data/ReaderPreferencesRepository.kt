package org.hello.reader.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReaderThemePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val Context.readerPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hello_reader_preferences",
)

/**
 * Persists typographic, visual theme, and audio customization settings in Jetpack DataStore.
 */
class ReaderPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.readerPreferencesDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal fun decodeSettings(raw: String): ReaderSettings {
        val decoded = json.decodeFromString(ReaderSettings.serializer(), raw)
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return decoded

        val hasLightKey = root.containsKey("selectedLightTheme")
        val hasDarkKey = root.containsKey("selectedDarkTheme")
        if (hasLightKey && hasDarkKey) return decoded

        val preset = decoded.themePreset
        val isNight = if (preset == ReaderThemePreset.CUSTOM) {
            decoded.customIsNight
        } else {
            preset.isNight
        }
        val themeKey = if (preset == ReaderThemePreset.CUSTOM) {
            "CUSTOM"
        } else {
            preset.name
        }

        return decoded.copy(
            selectedLightTheme = if (!hasLightKey && !isNight) themeKey else decoded.selectedLightTheme,
            selectedDarkTheme = if (!hasDarkKey && isNight) themeKey else decoded.selectedDarkTheme,
        )
    }

    val settings: Flow<ReaderSettings> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_SETTINGS_JSON] ?: return@map ReaderSettings()
        try {
            decodeSettings(raw)
        } catch (e: SerializationException) {
            ReaderSettings()
        }
    }

    suspend fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SETTINGS_JSON]?.let {
                runCatching { decodeSettings(it) }.getOrNull()
            } ?: ReaderSettings()
            val transformed = transform(current)
            val updated = transformed.copy(
                fontScalePercent = transformed.fontScalePercent.coerceIn(80, 250),
                lineHeightMultiplier = transformed.lineHeightMultiplier.coerceIn(1.0f, 2.5f),
                letterSpacingEm = transformed.letterSpacingEm.coerceIn(-0.05f, 0.25f),
                paragraphSpacingEm = transformed.paragraphSpacingEm.coerceIn(0.0f, 2.0f),
                boldWeight = transformed.boldWeight.coerceIn(0, 3),
            )
            prefs[KEY_SETTINGS_JSON] = json.encodeToString(ReaderSettings.serializer(), updated)
        }
    }

    val homeShelvesJson: Flow<String?> = homeShelvesJson(null)

    fun homeShelvesJson(profileKey: String?): Flow<String?> = dataStore.data.map { prefs ->
        val key = if (profileKey.isNullOrBlank()) KEY_HOME_SHELVES_JSON else stringPreferencesKey("home_shelves_${profileKey}")
        prefs[key] ?: prefs[KEY_HOME_SHELVES_JSON]
    }

    suspend fun saveHomeShelvesJson(rawJson: String, profileKey: String? = null) {
        val key = if (profileKey.isNullOrBlank()) KEY_HOME_SHELVES_JSON else stringPreferencesKey("home_shelves_${profileKey}")
        dataStore.edit { prefs ->
            prefs[key] = rawJson
        }
    }

    private companion object {
        val KEY_SETTINGS_JSON = stringPreferencesKey("reader_settings_json")
        val KEY_HOME_SHELVES_JSON = stringPreferencesKey("home_shelves_json")
    }
}
