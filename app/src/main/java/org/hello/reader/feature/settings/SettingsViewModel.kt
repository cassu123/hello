package org.hello.reader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.hello.reader.app.appContainer
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.dao.BookDao
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReaderThemePreset
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.security.SecurityVault
import org.hello.reader.feature.library.BookImportController
import org.hello.reader.feature.library.ImportEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SettingsViewModel(
    private val vault: SecurityVault,
    private val imports: BookImportController<Uri>,
    bookDao: BookDao,
    private val preferencesRepository: ReaderPreferencesRepository? = null,
) : ViewModel() {

    val activeProfile: StateFlow<ServerProfile?> = vault.activeProfile
    val profiles: StateFlow<List<ServerProfile>> = vault.profiles

    val localBooksCount: StateFlow<Int> = bookDao.observeLocalBooks()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val readerSettings: StateFlow<ReaderSettings> = preferencesRepository?.settings
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())
        ?: MutableStateFlow(ReaderSettings()).asStateFlow()

    /** The stored settings, waiting for the first read instead of returning the placeholder default. */
    suspend fun loadSettings(): ReaderSettings = preferencesRepository?.settings?.first() ?: ReaderSettings()

    /** Saves the custom theme and makes it the active one. */
    fun saveCustomTheme(name: String, isNight: Boolean, backgroundHex: String, foregroundHex: String, highlightHex: String) {
        updateSettings {
            it.copy(
                themePreset = ReaderThemePreset.CUSTOM,
                customThemeName = name.trim().ifBlank { "Custom" },
                customIsNight = isNight,
                customBackgroundHex = backgroundHex.normalizedHex(),
                customForegroundHex = foregroundHex.normalizedHex(),
                customHighlightHex = highlightHex.normalizedHex(),
                selectedLightTheme = if (!isNight) "CUSTOM" else it.selectedLightTheme,
                selectedDarkTheme = if (isNight) "CUSTOM" else it.selectedDarkTheme,
                darkModePreference = if (isNight) org.hello.reader.core.model.DarkModePreference.DARK else org.hello.reader.core.model.DarkModePreference.LIGHT,
            )
        }
    }

    fun selectLightTheme(key: String) {
        updateSettings { current ->
            val preset = org.hello.reader.core.model.ReaderThemePreset.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            val isShowingLight = when (current.darkModePreference) {
                org.hello.reader.core.model.DarkModePreference.LIGHT -> true
                org.hello.reader.core.model.DarkModePreference.DARK -> false
                org.hello.reader.core.model.DarkModePreference.DEVICE -> !current.isNightTheme
            }
            current.copy(
                selectedLightTheme = key,
                themePreset = if (isShowingLight && preset != null) preset else current.themePreset,
            )
        }
    }

    fun selectDarkTheme(key: String) {
        updateSettings { current ->
            val preset = org.hello.reader.core.model.ReaderThemePreset.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
            val isShowingDark = when (current.darkModePreference) {
                org.hello.reader.core.model.DarkModePreference.LIGHT -> false
                org.hello.reader.core.model.DarkModePreference.DARK -> true
                org.hello.reader.core.model.DarkModePreference.DEVICE -> current.isNightTheme
            }
            current.copy(
                selectedDarkTheme = key,
                themePreset = if (isShowingDark && preset != null) preset else current.themePreset,
            )
        }
    }

    /** Steps narration speed by [delta], kept within the player's 0.5x–3.5x range. */
    fun stepPlaybackSpeed(delta: Float) {
        updateSettings {
            val next = ((it.playbackSpeed + delta) * 100).roundToInt() / 100f
            it.copy(playbackSpeed = next.coerceIn(MIN_SPEED, MAX_SPEED))
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val repo = preferencesRepository ?: return
        viewModelScope.launch {
            repo.updateSettings(transform)
        }
    }

    /** Imports run app-wide, so they survive leaving Settings mid-copy. */
    val importsInProgress: StateFlow<Int> = imports.inProgress
    val importEvents: SharedFlow<ImportEvent> = imports.events

    fun selectProfile(id: String) {
        viewModelScope.launch {
            vault.setActiveProfile(id)
        }
    }

    fun disconnectProfile() {
        viewModelScope.launch {
            vault.clearActiveProfile()
        }
    }

    fun importBook(uri: Uri) = imports.import(uri)

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.5f

        val Factory = viewModelFactory {
            initializer {
                val container = appContainer
                SettingsViewModel(
                    vault = container.vault,
                    imports = container.bookImports,
                    preferencesRepository = container.readerPreferencesRepository,
                    bookDao = container.database.bookDao(),
                )
            }
        }
    }
}

/** "fafaf2" / " #FAFAF2 " -> "#FAFAF2". */
internal fun String.normalizedHex(): String = "#" + trim().removePrefix("#").uppercase(Locale.ROOT)
