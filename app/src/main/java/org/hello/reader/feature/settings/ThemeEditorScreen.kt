package org.hello.reader.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.core.model.WcagContrastValidator
import org.hello.reader.ui.components.SegmentedPills
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.parseColorOrNull
import androidx.compose.ui.res.stringResource
import org.hello.reader.R

private const val DayBackground = "#F1E9DC"
private const val DayForeground = "#2B241C"
private const val NightBackground = "#1B1C20"
private const val NightForeground = "#E3E1DC"
private const val DefaultHighlight = "#E0A458"

/** Full-screen editor for the single custom theme: live preview, name, day/night, and swatches. */
@Composable
fun ThemeEditorScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    var loaded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var isNight by rememberSaveable { mutableStateOf(false) }
    var bg by rememberSaveable { mutableStateOf(DayBackground) }
    var fg by rememberSaveable { mutableStateOf(DayForeground) }
    var hl by rememberSaveable { mutableStateOf(DefaultHighlight) }
    var showHex by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val settings = viewModel.loadSettings()
        if (settings.hasCustomTheme) {
            editing = true
            name = settings.customThemeName
            isNight = settings.customIsNight
            bg = settings.customBackgroundHex
            fg = settings.customForegroundHex
            hl = settings.customHighlightHex
        } else {
            // Start from whatever theme is active so "new" feels like a tweak, not a blank slate.
            name = ""
            isNight = settings.isNightTheme
            bg = settings.effectiveBackgroundHex
            fg = settings.effectiveForegroundHex
            hl = settings.effectiveHighlightHex
        }
        loaded = true
    }

    val bgColor = parseColorOrNull(bg)
    val fgColor = parseColorOrNull(fg)
    val hlColor = parseColorOrNull(hl)
    val canSave = loaded && bgColor != null && fgColor != null && hlColor != null && bg.trim().removePrefix("#").length == 6 &&
        fg.trim().removePrefix("#").length == 6 && hl.trim().removePrefix("#").length == 6

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.XXS, end = Spacing.M, top = Spacing.XXS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
            }
            Text(
                stringResource(if (editing) R.string.edit_theme else R.string.new_theme),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.saveCustomTheme(name, isNight, bg, fg, hl)
                    onBack()
                },
                enabled = canSave,
            ) { Text(stringResource(R.string.save)) }
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Gutter)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.Gutter),
        ) {
            Spacer(Modifier.height(Spacing.XS))
            ThemePreview(
                background = bgColor ?: Color.White,
                foreground = fgColor ?: Color.Black,
                highlight = hlColor ?: Color.Yellow,
                isNight = isNight,
                backgroundHex = bg,
                foregroundHex = fg,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.name)) },
                placeholder = { Text(stringResource(R.string.theme_name_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.Control),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mode), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                SegmentedPills(
                    options = listOf(false, true),
                    selected = isNight,
                    onSelect = { night ->
                        if (night != isNight) {
                            isNight = night
                            // Keep the swatches meaningful: a night theme on a light ground isn't one.
                            val bgIsLight = (parseColorOrNull(bg)?.luminance() ?: 1f) > 0.4f
                            if (night == bgIsLight) {
                                bg = if (night) NightBackground else DayBackground
                                fg = if (night) NightForeground else DayForeground
                            }
                        }
                    },
                    modifier = Modifier.width(200.dp),
                ) { night, selected ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            if (night) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(if (night) R.string.mode_night else R.string.mode_day),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ThemeSwatchRow(stringResource(R.string.color_background), bg, if (isNight) EditorNightBackgrounds else EditorDayBackgrounds, onPick = { bg = it })
            ThemeSwatchRow(stringResource(R.string.color_text), fg, if (isNight) EditorNightText else EditorDayText, onPick = { fg = it })
            ThemeSwatchRow(stringResource(R.string.color_highlight), hl, EditorHighlights, onPick = { hl = it })

            Column {
                TextButton(onClick = { showHex = !showHex }) {
                    Text(stringResource(R.string.custom_hex))
                    Spacer(Modifier.width(Spacing.XXS))
                    Icon(if (showHex) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                AnimatedVisibility(showHex) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        HexField(stringResource(R.string.color_background), bg) { bg = it }
                        HexField(stringResource(R.string.color_text), fg) { fg = it }
                        HexField(stringResource(R.string.color_highlight), hl) { hl = it }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.L))
        }
    }
}

@Composable
private fun HexField(label: String, value: String, onChange: (String) -> Unit) {
    val clean = value.trim().removePrefix("#")
    val invalid = clean.length != 6 || parseColorOrNull(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(9)) },
        label = { Text(label) },
        placeholder = { Text("#FAF8F2") },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(R.string.hex_invalid)) }) else null,
        leadingIcon = {
            Surface(
                color = parseColorOrNull(value) ?: Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .size(20.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            ) {}
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
        shape = RoundedCornerShape(Radius.Control),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A page in the draft theme's colors, with a highlighted phrase and a contrast rating. */
@Composable
private fun ThemePreview(
    background: Color,
    foreground: Color,
    highlight: Color,
    isNight: Boolean,
    backgroundHex: String,
    foregroundHex: String,
) {
    val report = WcagContrastValidator.evaluate(backgroundHex, foregroundHex)
    val before = stringResource(R.string.preview_text_before)
    val marked = stringResource(R.string.preview_text_highlight)
    val after = stringResource(R.string.preview_text_after)
    Surface(
        color = background,
        contentColor = foreground,
        shape = RoundedCornerShape(Radius.Card),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.Card)),
    ) {
        Column(Modifier.padding(Spacing.Gutter), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            Text(
                buildAnnotatedString {
                    append(before)
                    withStyle(SpanStyle(background = highlight.copy(alpha = if (isNight) 0.35f else 0.55f))) {
                        append(marked)
                    }
                    append(after)
                },
                fontFamily = Literata,
                fontSize = 17.sp,
                lineHeight = 27.sp,
                color = foreground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.preview_footer),
                    style = MaterialTheme.typography.labelMedium,
                    color = foreground.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                if (report != null) ContrastBadge(report)
            }
        }
    }
}
