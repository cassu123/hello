package org.hello.reader.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import org.hello.reader.core.model.DarkModePreference
import org.hello.reader.core.model.DefaultReadingProfiles
import org.hello.reader.core.util.DeviceProfile
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hello.reader.core.model.PageTurnAnimation
import org.hello.reader.core.model.ReaderFontFamily
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReaderThemePreset
import org.hello.reader.core.model.ReadingLayoutMode
import org.hello.reader.core.model.ReadingProfile
import org.hello.reader.core.model.WcagContrastValidator
import org.hello.reader.feature.reader.ReaderFontLoader
import org.hello.reader.feature.reader.builtInFontFamily
import org.hello.reader.ui.components.SegmentLabel
import org.hello.reader.ui.components.SegmentedPills
import org.hello.reader.ui.components.ThemeCard
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.parseColorOrNull
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.cardNameRes
import org.hello.reader.ui.components.chipName

/**
 * The reader's "Aa" sheet, on one page: theme, font, size, layout, line spacing and margins,
 * with the finer controls (weight, letter and paragraph spacing, justification, smart rewind)
 * folded under "More options".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editingCustom by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val isSystemDark = isSystemInDarkTheme()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Spacing.L),
        ) {
            SheetLabel(stringResource(R.string.reading_presets))
            ReadingProfilesRow(
                settings = settings,
                onSelectProfile = { profile ->
                    onUpdateSettings { it.applyProfile(profile) }
                },
                onSaveCurrent = { name ->
                    onUpdateSettings { current ->
                        val newProfile = ReadingProfile(
                            id = "custom_preset_${System.currentTimeMillis()}",
                            name = name,
                            fontFamily = current.fontFamily,
                            fontScalePercent = current.fontScalePercent,
                            boldWeight = current.boldWeight,
                            lineHeightMultiplier = current.lineHeightMultiplier,
                            letterSpacingEm = current.letterSpacingEm,
                            marginHorizontalDp = current.marginHorizontalDp,
                            layoutMode = current.layoutMode,
                            textJustified = current.textJustified,
                        )
                        current.copy(
                            readingProfiles = current.readingProfiles + newProfile,
                        )
                    }
                },
                onDeleteProfile = { profileId ->
                    onUpdateSettings { it.removeCustomReadingProfile(profileId) }
                },
            )
            Spacer(Modifier.height(Spacing.S))

            if (!DeviceProfile.isCurrentDeviceEInk && !settings.eInkOptimized) {
                SheetLabel(stringResource(R.string.sheet_brightness))
                BrightnessRow(settings, onUpdateSettings)
                Spacer(Modifier.height(Spacing.S))
            }

            SheetLabel(stringResource(R.string.sheet_theme))
            ThemeRow(
                settings = settings,
                onSelect = { preset ->
                    editingCustom = false
                    onUpdateSettings { it.withPresetSelected(preset, isSystemDark) }
                },
                onCustom = {
                    editingCustom = settings.themePreset == ReaderThemePreset.CUSTOM || !editingCustom
                    onUpdateSettings { it.withPresetSelected(ReaderThemePreset.CUSTOM, isSystemDark) }
                },
            )
            AnimatedVisibility(editingCustom && settings.themePreset == ReaderThemePreset.CUSTOM) {
                CustomThemeEditor(settings, isSystemDark, onUpdateSettings)
            }

            SheetLabel(stringResource(R.string.sheet_font))
            FontRow(settings, onUpdateSettings)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Column(Modifier.weight(1f)) {
                    SheetLabel(stringResource(R.string.sheet_size), inset = false)
                    SizeStepper(
                        percent = settings.fontScalePercent,
                        onChange = { value -> onUpdateSettings { it.copy(fontScalePercent = value) } },
                    )
                }
                Column(Modifier.weight(1f)) {
                    SheetLabel(stringResource(R.string.sheet_layout), inset = false)
                    SegmentedPills(
                        options = ReadingLayoutMode.entries,
                        selected = settings.layoutMode,
                        onSelect = { mode -> onUpdateSettings { it.copy(layoutMode = mode) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { mode, isSelected ->
                        SegmentLabel(stringResource(if (mode == ReadingLayoutMode.PAGED) R.string.layout_pages else R.string.layout_scroll), isSelected)
                    }
                }
            }

            if (settings.layoutMode == ReadingLayoutMode.PAGED) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Gutter),
                ) {
                    SheetLabel(stringResource(R.string.sheet_page_animation), inset = false)
                    SegmentedPills(
                        options = PageTurnAnimation.entries,
                        selected = settings.pageTurnAnimation,
                        onSelect = { anim -> onUpdateSettings { it.copy(pageTurnAnimation = anim) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { anim, isSelected ->
                        val label = when (anim) {
                            PageTurnAnimation.CURL -> stringResource(R.string.page_turn_curl)
                            PageTurnAnimation.SLIDE -> stringResource(R.string.page_turn_slide)
                            PageTurnAnimation.COVER -> stringResource(R.string.page_turn_cover)
                            PageTurnAnimation.NONE -> stringResource(R.string.page_turn_none)
                        }
                        SegmentLabel(label, isSelected)
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Gutter),
                ) {
                    SheetLabel(stringResource(R.string.setting_two_page_view), inset = false)
                    SegmentedPills(
                        options = org.hello.reader.core.model.TwoPageViewMode.entries,
                        selected = settings.twoPageViewMode,
                        onSelect = { mode -> onUpdateSettings { it.copy(twoPageViewMode = mode) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { mode, isSelected ->
                        val label = when (mode) {
                            org.hello.reader.core.model.TwoPageViewMode.AUTO -> stringResource(R.string.two_page_auto)
                            org.hello.reader.core.model.TwoPageViewMode.OFF -> stringResource(R.string.two_page_off)
                            org.hello.reader.core.model.TwoPageViewMode.ON -> stringResource(R.string.two_page_on)
                        }
                        SegmentLabel(label, isSelected)
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.S),
            ) {
                Column(Modifier.weight(1f)) {
                    SheetLabel(stringResource(R.string.line_spacing), inset = false)
                    SegmentedPills(
                        options = LineSpacingSteps,
                        selected = nearestStep(settings.lineHeightMultiplier, LineSpacingSteps),
                        onSelect = { value -> onUpdateSettings { it.copy(lineHeightMultiplier = value) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { value, isSelected ->
                        LinesGlyph(gap = LineSpacingSteps.indexOf(value), isSelected = isSelected, label = stringResource(LineSpacingLabels[LineSpacingSteps.indexOf(value)]))
                    }
                }
                Column(Modifier.weight(1f)) {
                    SheetLabel(stringResource(R.string.margins), inset = false)
                    SegmentedPills(
                        options = MarginSteps,
                        selected = nearestStep(settings.marginHorizontalDp, MarginSteps),
                        onSelect = { value -> onUpdateSettings { it.copy(marginHorizontalDp = value) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { value, isSelected ->
                        MarginGlyph(step = MarginSteps.indexOf(value), isSelected = isSelected, label = stringResource(MarginLabels[MarginSteps.indexOf(value)]))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.M))
            HorizontalDivider(Modifier.padding(horizontal = Spacing.Gutter), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { showMore = !showMore }
                    .padding(horizontal = Spacing.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.more_options), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                Icon(if (showMore) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(showMore) {
                MoreOptions(settings, onUpdateSettings)
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, inset: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(
            start = if (inset) Spacing.Gutter else 0.dp,
            top = Spacing.M,
            bottom = Spacing.XS + 2.dp,
        ),
    )
}

@Composable
private fun BrightnessRow(
    settings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Icon(
                Icons.Outlined.BrightnessLow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (settings.brightnessFollowSystem) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = settings.brightnessPercent.toFloat().coerceIn(1f, 100f),
                onValueChange = { raw ->
                    onUpdateSettings {
                        it.copy(
                            brightnessFollowSystem = false,
                            brightnessPercent = raw.roundToInt().coerceIn(1, 100),
                        )
                    }
                },
                valueRange = 1f..100f,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Outlined.BrightnessHigh,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (settings.brightnessFollowSystem) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (settings.brightnessFollowSystem) stringResource(R.string.brightness_follow_system) else "${settings.brightnessPercent}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 40.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .toggleable(
                    value = settings.brightnessFollowSystem,
                    role = Role.Switch,
                    onValueChange = { follow ->
                        onUpdateSettings { it.copy(brightnessFollowSystem = follow) }
                    },
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.brightness_follow_system),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = settings.brightnessFollowSystem,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun ThemeRow(
    settings: ReaderSettings,
    onSelect: (ReaderThemePreset) -> Unit,
    onCustom: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        ReaderThemePreset.entries.filter { it != ReaderThemePreset.CUSTOM }.forEach { preset ->
            ThemeCard(
                name = stringResource(preset.cardNameRes()),
                background = parseColorOrNull(preset.backgroundHex) ?: Color.White,
                foreground = parseColorOrNull(preset.foregroundHex) ?: Color.Black,
                selected = settings.themePreset == preset,
                onClick = { onSelect(preset) },
            )
        }
        CustomThemeCard(settings, selected = settings.themePreset == ReaderThemePreset.CUSTOM, onClick = onCustom)
    }
}

/** The custom slot: a dashed "+" until the user has picked custom colors, then a card in those colors. */
@Composable
private fun CustomThemeCard(settings: ReaderSettings, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        ThemeCard(
            name = settings.effectiveThemeName,
            background = parseColorOrNull(settings.customBackgroundHex) ?: Color.White,
            foreground = parseColorOrNull(settings.customForegroundHex) ?: Color.Black,
            selected = true,
            onClick = onClick,
        )
        return
    }
    Column(
        Modifier
            .width(56.dp)
            .selectable(selected = false, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 68.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.theme_custom), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Live custom-theme editor: name, day/night, and swatch rows. Applies as you tap. */
@Composable
private fun CustomThemeEditor(
    settings: ReaderSettings,
    isSystemDark: Boolean,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    var name by remember { mutableStateOf(settings.effectiveThemeName) }
    val contrast = WcagContrastValidator.evaluate(settings.customBackgroundHex, settings.customForegroundHex)
    val night = settings.customIsNight

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.Gutter, end = Spacing.Gutter, top = Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            OutlinedTextField(
                value = name,
                onValueChange = { value ->
                    name = value
                    onUpdateSettings { it.copy(customThemeName = value) }
                },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            SegmentedPills(
                options = listOf(false, true),
                selected = night,
                onSelect = { isNight ->
                    onUpdateSettings { current ->
                        val currentlyInDark = when (current.darkModePreference) {
                            DarkModePreference.LIGHT -> false
                            DarkModePreference.DARK -> true
                            DarkModePreference.DEVICE -> isSystemDark
                        }
                        val newPreference = if (isNight != currentlyInDark) {
                            if (isNight) DarkModePreference.DARK else DarkModePreference.LIGHT
                        } else {
                            current.darkModePreference
                        }
                        val swapped = if (isNight != current.customIsNight) {
                            current.copy(
                                customBackgroundHex = if (isNight) EditorNightBackgrounds[2] else EditorDayBackgrounds[2],
                                customForegroundHex = if (isNight) EditorNightText[1] else EditorDayText[1],
                            )
                        } else {
                            current
                        }
                        swapped.copy(
                            customIsNight = isNight,
                            selectedDarkTheme = if (isNight) "CUSTOM" else current.selectedDarkTheme,
                            selectedLightTheme = if (!isNight) "CUSTOM" else current.selectedLightTheme,
                            darkModePreference = newPreference,
                        )
                    }
                },
                modifier = Modifier
                    .width(112.dp)
                    .padding(top = 6.dp),
                height = 48.dp,
            ) { isNight, isSelected ->
                Icon(
                    if (isNight) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                    contentDescription = stringResource(if (isNight) R.string.mode_night else R.string.mode_day),
                    tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // Same palettes and swatches as the full-screen theme editor in Settings.
        ThemeSwatchRow(
            label = stringResource(R.string.color_background),
            currentHex = settings.customBackgroundHex,
            palette = if (night) EditorNightBackgrounds else EditorDayBackgrounds,
            onPick = { hex -> onUpdateSettings { it.copy(customBackgroundHex = hex) } },
        )
        ThemeSwatchRow(
            label = stringResource(R.string.color_text),
            currentHex = settings.customForegroundHex,
            palette = if (night) EditorNightText else EditorDayText,
            onPick = { hex -> onUpdateSettings { it.copy(customForegroundHex = hex) } },
        )
        ThemeSwatchRow(
            label = stringResource(R.string.color_highlight),
            currentHex = settings.customHighlightHex,
            palette = EditorHighlights,
            onPick = { hex -> onUpdateSettings { it.copy(customHighlightHex = hex) } },
        )
        contrast?.let { ContrastBadge(it) }
    }
}

@Composable
private fun FontRow(
    settings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fontError by remember { mutableStateOf<String?>(null) }
    val fontNotUsable = stringResource(R.string.font_not_usable)

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Only persist a font that actually parses; otherwise the reader would
            // silently fall back to the default typeface on every open.
            if (ReaderFontLoader.load(context, uri.toString()) == null) {
                fontError = fontNotUsable
                return@launch
            }
            fontError = null
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val previous = settings.customFontUri
            onUpdateSettings { current ->
                current.copy(fontFamily = ReaderFontFamily.CUSTOM, customFontUri = uri.toString())
            }
            // Don't accumulate persisted grants for fonts the user replaced.
            if (previous != null && previous != uri.toString()) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        previous.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }
    val pickFont = { fontPickerLauncher.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "*/*")) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        ReaderFontFamily.entries.forEach { family ->
            val isCustom = family == ReaderFontFamily.CUSTOM
            val selected = settings.fontFamily == family
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(Radius.Control))
                    .border(
                        if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(Radius.Control),
                    )
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (isCustom && settings.customFontUri == null) pickFont() else onUpdateSettings { it.copy(fontFamily = family) }
                        },
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    family.chipName(stringResource(R.string.your_font)),
                    fontFamily = if (isCustom) null else builtInFontFamily(family),
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            }
        }
    }
    if (settings.fontFamily == ReaderFontFamily.CUSTOM || fontError != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Gutter, end = Spacing.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                fontError ?: stringResource(R.string.using_imported_font),
                style = MaterialTheme.typography.bodySmall,
                color = if (fontError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = pickFont) { Text(stringResource(if (settings.customFontUri == null) R.string.font_import else R.string.font_replace)) }
        }
    }
}

@Composable
private fun SizeStepper(percent: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(Radius.Control))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("A", 14, stringResource(R.string.smaller_text), enabled = percent > MIN_FONT_SCALE) {
            onChange(stepFontScale(percent, -1))
        }
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("A", 20, stringResource(R.string.larger_text), enabled = percent < MAX_FONT_SCALE) {
            onChange(stepFontScale(percent, +1))
        }
    }
}

@Composable
private fun StepButton(glyph: String, size: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    // The glyph is a size sample, not a word, so it isn't translated.
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(Radius.Small))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontFamily = Literata,
            fontWeight = FontWeight.SemiBold,
            fontSize = size.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/** Three stacked lines with growing gaps; [gap] 0..2. */
@Composable
private fun LinesGlyph(gap: Int, isSelected: Boolean, label: String) {
    val color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy((2 + gap * 2).dp),
    ) {
        repeat(3) { Box(Modifier.size(width = 18.dp, height = 2.dp).background(color)) }
    }
}

/** Text block between two page edges; wider margins shrink the block. [step] 0..2. */
@Composable
private fun MarginGlyph(step: Int, isSelected: Boolean, label: String) {
    val color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 2.dp, height = 18.dp).background(color))
        Spacer(Modifier.width((2 + step * 2).dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Box(Modifier.size(width = (14 - step * 4).dp, height = 2.dp).background(color)) }
        }
        Spacer(Modifier.width((2 + step * 2).dp))
        Box(Modifier.size(width = 2.dp, height = 18.dp).background(color))
    }
}

@Composable
private fun MoreOptions(
    settings: ReaderSettings,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = Spacing.Gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        LabeledSlider(
            label = stringResource(R.string.boldness),
            value = stringResource(BOLDNESS_LABELS[settings.boldWeight.coerceIn(0, BOLDNESS_LABELS.lastIndex)]),
        ) {
            Slider(
                value = settings.boldWeight.coerceIn(0, 3).toFloat(),
                onValueChange = { raw -> onUpdateSettings { it.copy(boldWeight = raw.roundToInt().coerceIn(0, 3)) } },
                valueRange = 0f..3f,
                steps = 2,
            )
        }
        LabeledSlider(
            label = stringResource(R.string.letter_spacing),
            value = formatLetterSpacing(settings.letterSpacingEm, stringResource(R.string.letter_spacing_normal)),
        ) {
            Slider(
                value = settings.letterSpacingEm,
                onValueChange = { raw -> onUpdateSettings { it.copy(letterSpacingEm = snapLetterSpacing(raw)) } },
                valueRange = LETTER_SPACING_MIN_EM..LETTER_SPACING_MAX_EM,
            )
        }
        LabeledSlider(label = stringResource(R.string.line_spacing), value = String.format(Locale.US, "%.2f×", settings.lineHeightMultiplier)) {
            Slider(
                value = settings.lineHeightMultiplier,
                onValueChange = { raw -> onUpdateSettings { it.copy(lineHeightMultiplier = ((raw * 20f).roundToInt() / 20f).coerceIn(1.0f, 2.5f)) } },
                valueRange = 1.0f..2.5f,
            )
        }
        LabeledSlider(label = stringResource(R.string.paragraph_spacing), value = String.format(Locale.US, "%.1f em", settings.paragraphSpacingEm)) {
            Slider(
                value = settings.paragraphSpacingEm.coerceIn(0f, 2f),
                onValueChange = { raw -> onUpdateSettings { it.copy(paragraphSpacingEm = ((raw * 10f).roundToInt() / 10f).coerceIn(0f, 2f)) } },
                valueRange = 0f..2f,
            )
        }
        LabeledSlider(label = stringResource(R.string.margins), value = stringResource(R.string.margin_dp, settings.marginHorizontalDp)) {
            Slider(
                value = settings.marginHorizontalDp.toFloat(),
                onValueChange = { raw -> onUpdateSettings { it.copy(marginHorizontalDp = raw.roundToInt().coerceIn(8, 48)) } },
                valueRange = 8f..48f,
            )
        }
        SwitchRow(
            title = stringResource(R.string.justify_text),
            checked = settings.textJustified,
            onCheckedChange = { enabled -> onUpdateSettings { it.copy(textJustified = enabled) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_volume_buttons_turn_pages),
            checked = settings.volumeButtonsTurnPages,
            onCheckedChange = { enabled -> onUpdateSettings { it.copy(volumeButtonsTurnPages = enabled) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_e_ink_mode),
            checked = settings.eInkOptimized,
            onCheckedChange = { enabled -> onUpdateSettings { it.copy(eInkOptimized = enabled) } },
        )
        if (settings.eInkOptimized || DeviceProfile.isCurrentDeviceEInk) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = Spacing.XXS),
            ) {
                SheetLabel(stringResource(R.string.setting_full_refresh_interval), inset = false)
                Text(
                    stringResource(R.string.setting_full_refresh_interval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.XS),
                )
                val refreshOptions = listOf(0, 5, 10, 20)
                SegmentedPills(
                    options = refreshOptions,
                    selected = nearestStep(settings.fullRefreshIntervalPages, refreshOptions),
                    onSelect = { interval -> onUpdateSettings { it.copy(fullRefreshIntervalPages = interval) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { interval, isSelected ->
                    val label = when (interval) {
                        0 -> stringResource(R.string.full_refresh_off)
                        5 -> stringResource(R.string.full_refresh_5)
                        10 -> stringResource(R.string.full_refresh_10)
                        20 -> stringResource(R.string.full_refresh_20)
                        else -> "$interval"
                    }
                    SegmentLabel(label, isSelected)
                }
            }
        }
        SwitchRow(
            title = stringResource(R.string.smart_rewind),
            subtitle = stringResource(R.string.smart_rewind_detail),
            checked = settings.smartRewindEnabled,
            onCheckedChange = { enabled -> onUpdateSettings { it.copy(smartRewindEnabled = enabled) } },
        )
        SwitchRow(
            title = stringResource(R.string.setting_always_show_back_to_reading),
            subtitle = stringResource(R.string.setting_always_show_back_to_reading_detail),
            checked = settings.alwaysShowBackToReadingPosition,
            onCheckedChange = { enabled -> onUpdateSettings { it.copy(alwaysShowBackToReadingPosition = enabled) } },
        )
    }
}

private val BOLDNESS_LABELS = listOf(R.string.weight_regular, R.string.weight_medium, R.string.weight_semibold, R.string.weight_bold)

@Composable
private fun LabeledSlider(label: String, value: String, slider: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        slider()
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

internal const val MIN_FONT_SCALE = 80
internal const val MAX_FONT_SCALE = 250

/** Steps text size by 10%, snapping off-grid values (e.g. 105%) onto the grid first. */
internal fun stepFontScale(current: Int, direction: Int): Int {
    val snapped = (current / 10) * 10
    val next = if (direction > 0) snapped + 10 else if (snapped == current) snapped - 10 else snapped
    return next.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
}

internal val LineSpacingSteps = listOf(1.3f, 1.5f, 1.8f)
private val LineSpacingLabels = listOf(R.string.line_spacing_tight, R.string.line_spacing_normal, R.string.line_spacing_loose)
internal val MarginSteps = listOf(12, 24, 40)
private val MarginLabels = listOf(R.string.margins_narrow, R.string.margins_medium, R.string.margins_wide)

/** The step closest to [value], so custom slider values still light up the nearest preset. */
internal fun nearestStep(value: Float, steps: List<Float>): Float = steps.minBy { abs(it - value) }

internal fun nearestStep(value: Int, steps: List<Int>): Int = steps.minBy { abs(it - value) }

internal const val LETTER_SPACING_MIN_EM = -0.05f
internal const val LETTER_SPACING_MAX_EM = 0.25f

/** Snaps to 0.01em steps so the persisted value and the label agree. */
internal fun snapLetterSpacing(raw: Float): Float =
    ((raw * 100f).roundToInt() / 100f).coerceIn(LETTER_SPACING_MIN_EM, LETTER_SPACING_MAX_EM)

/** [normalLabel] is shown for zero spacing. */
internal fun formatLetterSpacing(em: Float, normalLabel: String): String = when {
    em == 0f -> normalLabel
    em > 0f -> String.format(Locale.US, "+%.2f em", em)
    else -> String.format(Locale.US, "%.2f em", em)
}

@Composable
private fun ReadingProfilesRow(
    settings: ReaderSettings,
    onSelectProfile: (ReadingProfile) -> Unit,
    onSaveCurrent: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var profileToDelete by remember { mutableStateOf<ReadingProfile?>(null) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        settings.readingProfiles.forEach { profile ->
            val isSelected = profile.matches(settings)
            val isCustom = DefaultReadingProfiles.none { it.id == profile.id }
            val displayName = when (profile.id) {
                "preset_default" -> stringResource(R.string.profile_default)
                "preset_large" -> stringResource(R.string.profile_large_print)
                "preset_compact" -> stringResource(R.string.profile_compact)
                "preset_scroll" -> stringResource(R.string.profile_continuous)
                else -> profile.name
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelectProfile(profile) },
                label = { Text(displayName) },
                trailingIcon = if (isCustom) {
                    {
                        IconButton(
                            onClick = { profileToDelete = profile },
                            modifier = Modifier.size(16.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.delete_profile),
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                } else null,
                shape = RoundedCornerShape(Radius.Control),
            )
        }
        AssistChip(
            onClick = { showSaveDialog = true },
            label = { Text(stringResource(R.string.save_preset)) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            shape = RoundedCornerShape(Radius.Control),
        )
    }

    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text(stringResource(R.string.delete_profile)) },
            text = { Text(stringResource(R.string.delete_profile_confirm, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(target.id)
                        profileToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.save_preset)) },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it.take(30) },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newProfileName.trim()
                        if (trimmed.isNotEmpty()) {
                            onSaveCurrent(trimmed)
                            newProfileName = ""
                            showSaveDialog = false
                        }
                    },
                    enabled = newProfileName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
