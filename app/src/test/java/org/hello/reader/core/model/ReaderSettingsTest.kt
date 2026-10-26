package org.hello.reader.core.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.hello.reader.core.data.ReaderPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `contrast ratios match WCAG reference values`() {
        // Black on white is the maximum, 21:1.
        val max = WcagContrastValidator.evaluate("#FFFFFF", "#000000")!!
        assertEquals(21.0, max.ratio, 0.0)
        assertTrue(max.passesAAA)
        // Order of background/foreground doesn't matter.
        assertEquals(max, WcagContrastValidator.evaluate("#000000", "#FFFFFF"))
        // #767676 on white is the classic "just passes AA" grey (4.54:1).
        val aa = WcagContrastValidator.evaluate("#FFFFFF", "#767676")!!
        assertEquals(4.54, aa.ratio, 0.0)
        assertTrue(aa.passesAA)
        assertFalse(aa.passesAAA)
        // #777777 on white fails AA (4.48:1).
        val fail = WcagContrastValidator.evaluate("#FFFFFF", "#777777")!!
        assertEquals(4.48, fail.ratio, 0.0)
        assertFalse(fail.passesAA)
        // Identical colors are 1:1.
        assertEquals(1.0, WcagContrastValidator.evaluate("#3B2E1E", "#3B2E1E")!!.ratio, 0.0)
    }

    @Test
    fun `contrast accepts ARGB and rejects malformed hex`() {
        assertEquals(21.0, WcagContrastValidator.evaluate("#FFFFFFFF", "FF000000")!!.ratio, 0.0)
        assertNull(WcagContrastValidator.evaluate("#FFF", "#000000"))
        assertNull(WcagContrastValidator.evaluate("#FFFFFF", "#00000G"))
        assertNull(WcagContrastValidator.evaluate("", "#000000"))
    }

    @Test
    fun `custom theme colors only apply with the custom preset`() {
        val custom = ReaderSettings(customBackgroundHex = "#101010", customForegroundHex = "#EFEFEF")
        assertEquals(ReaderThemePreset.PAPER.backgroundHex, custom.effectiveBackgroundHex)
        val selected = custom.copy(themePreset = ReaderThemePreset.CUSTOM)
        assertEquals("#101010", selected.effectiveBackgroundHex)
        assertEquals("#EFEFEF", selected.effectiveForegroundHex)
    }

    @Test
    fun `preferences repository clamps typography and round-trips custom font and letter spacing`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("p.preferences_pb").also { it.delete() } },
            )
            assertEquals(ReaderSettings(), repo.settings.first())

            repo.updateSettings {
                it.copy(
                    fontScalePercent = 400,
                    lineHeightMultiplier = 0.2f,
                    letterSpacingEm = 1f,
                    fontFamily = ReaderFontFamily.CUSTOM,
                    customFontUri = "content://fonts/42",
                )
            }
            val saved = repo.settings.first()
            assertEquals(250, saved.fontScalePercent)
            assertEquals(1.0f, saved.lineHeightMultiplier, 0f)
            assertEquals(0.25f, saved.letterSpacingEm, 0f)
            assertEquals(ReaderFontFamily.CUSTOM, saved.fontFamily)
            assertEquals("content://fonts/42", saved.customFontUri)

            repo.updateSettings { it.copy(fontScalePercent = 10, letterSpacingEm = -1f) }
            val low = repo.settings.first()
            assertEquals(80, low.fontScalePercent)
            assertEquals(-0.05f, low.letterSpacingEm, 0f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `custom theme name and day night mode persist and resolve effective properties`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("theme.preferences_pb").also { it.delete() } },
            )
            repo.updateSettings {
                it.copy(
                    themePreset = ReaderThemePreset.CUSTOM,
                    customThemeName = "Sweetie Amber",
                    customIsNight = true,
                    customBackgroundHex = "#1A1412",
                    customForegroundHex = "#F2E8DF",
                    customHighlightHex = "#E07A5F",
                )
            }
            val saved = repo.settings.first()
            assertEquals(ReaderThemePreset.CUSTOM, saved.themePreset)
            assertEquals("Sweetie Amber", saved.effectiveThemeName)
            assertTrue(saved.isNightTheme)
            assertEquals("#1A1412", saved.effectiveBackgroundHex)
            assertEquals("#F2E8DF", saved.effectiveForegroundHex)
            assertEquals("#E07A5F", saved.effectiveHighlightHex)

            // Preset fallback when preset is not CUSTOM
            val preset = saved.copy(themePreset = ReaderThemePreset.WARM_SEPIA)
            assertEquals("Warm Sepia", preset.effectiveThemeName)
            assertFalse(preset.isNightTheme)
            assertEquals("#F4ECD8", preset.effectiveBackgroundHex)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `withPresetSelected updates day or night slot and flips mode when necessary`() {
        val lightSettings = ReaderSettings(
            darkModePreference = DarkModePreference.LIGHT,
            selectedLightTheme = ReaderThemePreset.PAPER.name,
            selectedDarkTheme = ReaderThemePreset.NIGHT.name,
        )

        // Selecting a night preset while in light mode flips to dark mode
        val flippedDark = lightSettings.withPresetSelected(ReaderThemePreset.SLATE_GRAY)
        assertEquals(ReaderThemePreset.SLATE_GRAY, flippedDark.themePreset)
        assertEquals(ReaderThemePreset.SLATE_GRAY.name, flippedDark.selectedDarkTheme)
        assertEquals(DarkModePreference.DARK, flippedDark.darkModePreference)

        // Selecting a light preset while in dark mode flips to light mode
        val flippedLight = flippedDark.withPresetSelected(ReaderThemePreset.GREEN_TEA)
        assertEquals(ReaderThemePreset.GREEN_TEA, flippedLight.themePreset)
        assertEquals(ReaderThemePreset.GREEN_TEA.name, flippedLight.selectedLightTheme)
        assertEquals(DarkModePreference.LIGHT, flippedLight.darkModePreference)
    }

    @Test
    fun `reading profile matching detects slider changes and deletion`() {
        val settings = ReaderSettings()
        val defaultProfile = DefaultReadingProfiles.first { it.id == "preset_default" }
        assertTrue(defaultProfile.matches(settings))

        // Adjusting font scale breaks the match so active profile is un-highlighted
        val modified = settings.copy(fontScalePercent = 120)
        assertFalse(defaultProfile.matches(modified))

        // Custom profile creation and deletion
        val custom = ReadingProfile(
            id = "custom_1",
            name = "My Profile",
            fontScalePercent = 120,
        )
        val withCustom = modified.copy(readingProfiles = modified.readingProfiles + custom)
        assertTrue(custom.matches(withCustom))

        val deleted = withCustom.removeCustomReadingProfile("custom_1")
        assertFalse(deleted.readingProfiles.any { it.id == "custom_1" })
    }

    @Test
    fun `picking Paper as light theme or Night as dark theme in settings is preserved even after choosing another theme`() {
        val settings = ReaderSettings(
            themePreset = ReaderThemePreset.WARM_SEPIA,
            selectedLightTheme = ReaderThemePreset.WARM_SEPIA.name,
            darkModePreference = DarkModePreference.LIGHT,
        )
        val updated = settings.copy(selectedLightTheme = ReaderThemePreset.PAPER.name)
        val resolved = updated.withDayNightResolved(isSystemInDark = false)
        assertEquals(ReaderThemePreset.PAPER, resolved.themePreset)
        assertEquals(ReaderThemePreset.PAPER.name, resolved.selectedLightTheme)

        val darkSettings = ReaderSettings(
            themePreset = ReaderThemePreset.DUNE,
            selectedDarkTheme = ReaderThemePreset.DUNE.name,
            darkModePreference = DarkModePreference.DARK,
        )
        val updatedDark = darkSettings.copy(selectedDarkTheme = ReaderThemePreset.NIGHT.name)
        val resolvedDark = updatedDark.withDayNightResolved(isSystemInDark = true)
        assertEquals(ReaderThemePreset.NIGHT, resolvedDark.themePreset)
        assertEquals(ReaderThemePreset.NIGHT.name, resolvedDark.selectedDarkTheme)
    }

    @Test
    fun `device mode flips to dark or light when theme selected in Aa sheet and custom night theme is dark`() {
        val deviceSettings = ReaderSettings(
            darkModePreference = DarkModePreference.DEVICE,
            selectedLightTheme = ReaderThemePreset.PAPER.name,
            selectedDarkTheme = ReaderThemePreset.NIGHT.name,
        )

        // Selecting dark preset while system is light flips to DARK
        val pickedNight = deviceSettings.withPresetSelected(ReaderThemePreset.NIGHT, isSystemInDark = false)
        assertEquals(DarkModePreference.DARK, pickedNight.darkModePreference)
        assertEquals(ReaderThemePreset.NIGHT.name, pickedNight.selectedDarkTheme)
        val resolvedAtDay = pickedNight.withDayNightResolved(isSystemInDark = false)
        assertEquals(ReaderThemePreset.NIGHT, resolvedAtDay.themePreset)

        // Selecting light preset while showing dark flips to LIGHT
        val pickedSepia = pickedNight.withPresetSelected(ReaderThemePreset.WARM_SEPIA, isSystemInDark = true)
        assertEquals(DarkModePreference.LIGHT, pickedSepia.darkModePreference)
        assertEquals(ReaderThemePreset.WARM_SEPIA.name, pickedSepia.selectedLightTheme)
        val resolvedAtNight = pickedSepia.withDayNightResolved(isSystemInDark = true)
        assertEquals(ReaderThemePreset.WARM_SEPIA, resolvedAtNight.themePreset)

        val customNight = ReaderSettings(
            darkModePreference = DarkModePreference.LIGHT,
            themePreset = ReaderThemePreset.CUSTOM,
            customIsNight = true,
            customThemeName = "Night Owl",
        ).withPresetSelected(ReaderThemePreset.CUSTOM, isSystemInDark = false)
        assertEquals("CUSTOM", customNight.selectedDarkTheme)
        assertEquals(DarkModePreference.DARK, customNight.darkModePreference)

        val customDay = ReaderSettings(
            darkModePreference = DarkModePreference.DARK,
            themePreset = ReaderThemePreset.CUSTOM,
            customIsNight = false,
            customThemeName = "Sunny Day",
        ).withPresetSelected(ReaderThemePreset.CUSTOM, isSystemInDark = true)
        assertEquals("CUSTOM", customDay.selectedLightTheme)
        assertEquals(DarkModePreference.LIGHT, customDay.darkModePreference)
    }

    @Test
    fun `DEVICE with system light tapping a light preset stays DEVICE`() {
        val deviceSettings = ReaderSettings(
            darkModePreference = DarkModePreference.DEVICE,
            selectedLightTheme = ReaderThemePreset.PAPER.name,
            selectedDarkTheme = ReaderThemePreset.NIGHT.name,
        )

        // When phone is in light mode, tapping Warm Sepia (light) should stay DEVICE
        val updated = deviceSettings.withPresetSelected(ReaderThemePreset.WARM_SEPIA, isSystemInDark = false)
        assertEquals(DarkModePreference.DEVICE, updated.darkModePreference)
        assertEquals(ReaderThemePreset.WARM_SEPIA, updated.themePreset)
        assertEquals(ReaderThemePreset.WARM_SEPIA.name, updated.selectedLightTheme)

        // At night, it will still switch to night theme
        val resolvedAtNight = updated.withDayNightResolved(isSystemInDark = true)
        assertEquals(ReaderThemePreset.NIGHT, resolvedAtNight.themePreset)

        // And in daytime, it shows Warm Sepia
        val resolvedAtDay = updated.withDayNightResolved(isSystemInDark = false)
        assertEquals(ReaderThemePreset.WARM_SEPIA, resolvedAtDay.themePreset)
    }

    @Test
    fun `legacy preferences without selectedLightTheme or selectedDarkTheme migrate from themePreset`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("migration.preferences_pb").also { it.delete() } },
            )
            // Legacy JSON simulating user who previously had WARM_SEPIA
            val legacySepiaJson = """{"themePreset":"WARM_SEPIA","darkModePreference":"DEVICE"}"""
            val decodedSepia = repo.decodeSettings(legacySepiaJson)
            assertEquals(ReaderThemePreset.WARM_SEPIA.name, decodedSepia.selectedLightTheme)
            assertEquals(ReaderThemePreset.NIGHT.name, decodedSepia.selectedDarkTheme)

            // Legacy JSON simulating user who previously had SLATE_GRAY
            val legacySlateJson = """{"themePreset":"SLATE_GRAY","darkModePreference":"DARK"}"""
            val decodedSlate = repo.decodeSettings(legacySlateJson)
            assertEquals(ReaderThemePreset.SLATE_GRAY.name, decodedSlate.selectedDarkTheme)
            assertEquals(ReaderThemePreset.PAPER.name, decodedSlate.selectedLightTheme)

            // Legacy JSON simulating user who had custom night theme
            val legacyCustomJson = """{"themePreset":"CUSTOM","customIsNight":true,"customThemeName":"Dark Star"}"""
            val decodedCustom = repo.decodeSettings(legacyCustomJson)
            assertEquals("CUSTOM", decodedCustom.selectedDarkTheme)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pageTurnAnimation defaults to CURL and round-trips through preferences`() = runBlocking {
        val defaultSettings = ReaderSettings()
        assertEquals(PageTurnAnimation.CURL, defaultSettings.pageTurnAnimation)
        assertEquals("Flip", PageTurnAnimation.CURL.displayName)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("anim_test.preferences_pb").also { it.delete() } },
            )
            val jsonSlide = """{"pageTurnAnimation":"SLIDE"}"""
            val decodedSlide = repo.decodeSettings(jsonSlide)
            assertEquals(PageTurnAnimation.SLIDE, decodedSlide.pageTurnAnimation)

            val jsonCover = """{"pageTurnAnimation":"COVER"}"""
            val decodedCover = repo.decodeSettings(jsonCover)
            assertEquals(PageTurnAnimation.COVER, decodedCover.pageTurnAnimation)

            val jsonNone = """{"pageTurnAnimation":"NONE"}"""
            val decodedNone = repo.decodeSettings(jsonNone)
            assertEquals(PageTurnAnimation.NONE, decodedNone.pageTurnAnimation)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `eInk and volume button settings round-trip through preferences`() = runBlocking {
        assertEquals("#FFFFFF", ReaderThemePreset.E_INK.backgroundHex)
        assertEquals("#000000", ReaderThemePreset.E_INK.foregroundHex)
        assertEquals("#333333", ReaderThemePreset.E_INK.highlightHex)
        assertFalse(ReaderThemePreset.E_INK.isNight)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("eink_test.preferences_pb").also { it.delete() } },
            )
            val json = """{"volumeButtonsTurnPages":true,"eInkOptimized":true,"themePreset":"E_INK","twoPageViewMode":"ON"}"""
            val decoded = repo.decodeSettings(json)
            assertTrue(decoded.volumeButtonsTurnPages)
            assertTrue(decoded.eInkOptimized)
            assertEquals(ReaderThemePreset.E_INK, decoded.themePreset)
            assertEquals(TwoPageViewMode.ON, decoded.twoPageViewMode)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `keepPlayingAfterAppClose defaults to false and round-trips through preferences`() = runBlocking {
        val defaultSettings = ReaderSettings()
        assertFalse(defaultSettings.keepPlayingAfterAppClose)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("keep_playing_test.preferences_pb").also { it.delete() } },
            )
            val jsonTrue = """{"keepPlayingAfterAppClose":true}"""
            val decodedTrue = repo.decodeSettings(jsonTrue)
            assertTrue(decodedTrue.keepPlayingAfterAppClose)

            val jsonFalse = """{"keepPlayingAfterAppClose":false}"""
            val decodedFalse = repo.decodeSettings(jsonFalse)
            assertFalse(decodedFalse.keepPlayingAfterAppClose)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `alwaysShowBackToReadingPosition defaults to false and round-trips through preferences`() = runBlocking {
        val defaultSettings = ReaderSettings()
        assertFalse(defaultSettings.alwaysShowBackToReadingPosition)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("back_to_reading_test.preferences_pb").also { it.delete() } },
            )
            val jsonTrue = """{"alwaysShowBackToReadingPosition":true}"""
            val decodedTrue = repo.decodeSettings(jsonTrue)
            assertTrue(decodedTrue.alwaysShowBackToReadingPosition)

            val jsonFalse = """{"alwaysShowBackToReadingPosition":false}"""
            val decodedFalse = repo.decodeSettings(jsonFalse)
            assertFalse(decodedFalse.alwaysShowBackToReadingPosition)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `brightness settings default to follow system and round-trip through preferences`() = runBlocking {
        val defaultSettings = ReaderSettings()
        assertTrue(defaultSettings.brightnessFollowSystem)
        assertEquals(100, defaultSettings.brightnessPercent)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("brightness_test.preferences_pb").also { it.delete() } },
            )
            val jsonManual = """{"brightnessFollowSystem":false,"brightnessPercent":65}"""
            val decodedManual = repo.decodeSettings(jsonManual)
            assertFalse(decodedManual.brightnessFollowSystem)
            assertEquals(65, decodedManual.brightnessPercent)

            val jsonFollow = """{"brightnessFollowSystem":true,"brightnessPercent":40}"""
            val decodedFollow = repo.decodeSettings(jsonFollow)
            assertTrue(decodedFollow.brightnessFollowSystem)
            assertEquals(40, decodedFollow.brightnessPercent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `fullRefreshIntervalPages round-trips through preferences`() = runBlocking {
        val defaultSettings = ReaderSettings()
        val expectedDefault = if (org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk) 10 else 0
        assertEquals(expectedDefault, defaultSettings.fullRefreshIntervalPages)

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val repo = ReaderPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("full_refresh_test.preferences_pb").also { it.delete() } },
            )
            val jsonInterval = """{"fullRefreshIntervalPages":20}"""
            val decoded = repo.decodeSettings(jsonInterval)
            assertEquals(20, decoded.fullRefreshIntervalPages)

            val jsonOff = """{"fullRefreshIntervalPages":0}"""
            val decodedOff = repo.decodeSettings(jsonOff)
            assertEquals(0, decodedOff.fullRefreshIntervalPages)
        } finally {
            scope.cancel()
        }
    }
}
