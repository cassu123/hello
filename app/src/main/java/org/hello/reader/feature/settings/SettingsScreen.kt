package org.hello.reader.feature.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.BuildConfig
import org.hello.reader.R
import org.hello.reader.core.model.DarkModePreference
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReaderThemePreset
import org.hello.reader.core.model.ReadingLayoutMode
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.feature.library.ImportEvent
import org.hello.reader.feature.library.LibraryFormatters
import org.hello.reader.feature.library.message
import org.hello.reader.ui.components.HelloWordmark
import org.hello.reader.ui.components.ScreenTitle
import org.hello.reader.ui.components.SegmentLabel
import org.hello.reader.ui.components.SegmentedPills
import org.hello.reader.ui.components.SettingsDivider
import org.hello.reader.ui.components.SettingsGroup
import org.hello.reader.ui.components.SettingsRow
import org.hello.reader.ui.components.SettingsSwitchRow
import org.hello.reader.ui.components.StatusDot
import org.hello.reader.ui.components.chipName
import org.hello.reader.ui.theme.LocalHelloColors
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing

/** File types offered by the import picker; the importer rejects anything it can't open. */
private val ImportMimeTypes = arrayOf("application/epub+zip", "audio/*", "application/octet-stream", "*/*")

/**
 * Settings tab. Adding a server goes through [onAddServer] (the Connect screen).
 */
@Composable
fun SettingsScreen(
    onAddServer: () -> Unit,
    onScanQr: () -> Unit,
    onOpenThemeEditor: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val localCount by viewModel.localBooksCount.collectAsStateWithLifecycle()
    val settings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val importing by viewModel.importsInProgress.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val openLink: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: ActivityNotFoundException) {
            // Ignore if the device has no browser installed.
        } catch (_: IllegalArgumentException) {
            // Ignore if no handler can resolve the URI.
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBook(uri)
    }

    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.importEvents.collect { event ->
            val message = when (event) {
                is ImportEvent.Imported -> context.getString(R.string.import_added, event.book.title)
                is ImportEvent.Failed -> event.message(context)
            }
            snackbars.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.L),
        ) {
            ScreenTitle(stringResource(R.string.settings))

            val profile = activeProfile
            if (profile != null) {
                ServerCard(
                    profile = profile,
                    profiles = profiles,
                    onSwitch = viewModel::selectProfile,
                    onAddServer = onAddServer,
                    onDisconnect = viewModel::disconnectProfile,
                )
            } else {
                NoServerCard(
                    onAddServer = onAddServer,
                    onScanQr = onScanQr,
                    // A saved profile can be reactivated without retyping its address.
                    savedProfiles = profiles,
                    onResume = { viewModel.selectProfile(it.id) },
                )
            }

            val defaultCustomDayName = stringResource(R.string.custom_day_theme_name)
            val defaultCustomNightName = stringResource(R.string.custom_night_theme_name)

            val lightThemes = remember(settings.hasCustomTheme, settings.customIsNight, settings.customThemeName, defaultCustomDayName) {
                val list = ReaderThemePreset.entries
                    .filter { !it.isNight && it != ReaderThemePreset.CUSTOM }
                    .map { it.name to it.displayName }
                    .toMutableList()
                if (settings.hasCustomTheme && !settings.customIsNight) {
                    list.add("CUSTOM" to settings.customThemeName.ifBlank { defaultCustomDayName })
                }
                list
            }

            val darkThemes = remember(settings.hasCustomTheme, settings.customIsNight, settings.customThemeName, defaultCustomNightName) {
                val list = ReaderThemePreset.entries
                    .filter { it.isNight && it != ReaderThemePreset.CUSTOM }
                    .map { it.name to it.displayName }
                    .toMutableList()
                if (settings.hasCustomTheme && settings.customIsNight) {
                    list.add("CUSTOM" to settings.customThemeName.ifBlank { defaultCustomNightName })
                }
                list
            }

            SettingsGroup(stringResource(R.string.appearance)) {
                Spacer(Modifier.height(Spacing.M))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Gutter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.dark_mode),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SegmentedPills(
                        options = DarkModePreference.entries,
                        selected = settings.darkModePreference,
                        onSelect = { mode -> viewModel.updateSettings { it.copy(darkModePreference = mode) } },
                    ) { mode, isSelected ->
                        val label = when (mode) {
                            DarkModePreference.LIGHT -> stringResource(R.string.dark_mode_light)
                            DarkModePreference.DEVICE -> stringResource(R.string.dark_mode_device)
                            DarkModePreference.DARK -> stringResource(R.string.dark_mode_dark)
                        }
                        SegmentLabel(label, isSelected)
                    }
                }
                Spacer(Modifier.height(Spacing.S))
                SettingsDivider()
                ThemePickerRow(
                    title = stringResource(R.string.light_theme),
                    selectedName = settings.selectedLightTheme,
                    presets = lightThemes,
                    onSelect = { key -> viewModel.selectLightTheme(key) },
                )
                SettingsDivider()
                ThemePickerRow(
                    title = stringResource(R.string.dark_theme),
                    selectedName = settings.selectedDarkTheme,
                    presets = darkThemes,
                    onSelect = { key -> viewModel.selectDarkTheme(key) },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.manage_custom_themes),
                    supporting = stringResource(R.string.manage_custom_themes_hint),
                    value = if (settings.hasCustomTheme) settings.customThemeName.ifBlank { stringResource(R.string.theme_custom) } else null,
                    leading = Icons.Outlined.Palette,
                    onClick = onOpenThemeEditor,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.use_reader_theme_everywhere),
                    supporting = if (settings.appUsesReaderTheme) null else stringResource(R.string.app_follows_system_theme),
                    checked = settings.appUsesReaderTheme,
                    onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(appUsesReaderTheme = enabled) } },
                )
            }

            SettingsGroup(stringResource(R.string.reading_and_listening)) {
                SettingsRow(
                    title = stringResource(R.string.font_and_layout),
                    supporting = stringResource(R.string.font_and_layout_hint),
                    value = fontAndLayoutSummary(
                        settings,
                        pagesLabel = stringResource(R.string.layout_pages),
                        scrollLabel = stringResource(R.string.layout_scroll),
                        customFontLabel = stringResource(R.string.your_font),
                    ),
                )
                SettingsDivider()
                SpeedRow(
                    speed = settings.playbackSpeed,
                    onStep = viewModel::stepPlaybackSpeed,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.smart_rewind),
                    supporting = stringResource(R.string.smart_rewind_hint),
                    checked = settings.smartRewindEnabled,
                    onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(smartRewindEnabled = enabled) } },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.keep_playing_after_app_close),
                    supporting = stringResource(R.string.keep_playing_after_app_close_hint),
                    checked = settings.keepPlayingAfterAppClose,
                    onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(keepPlayingAfterAppClose = enabled) } },
                )
            }

            SettingsGroup(stringResource(R.string.library)) {
                SettingsRow(
                    title = stringResource(if (importing > 0) R.string.importing else R.string.import_book),
                    supporting = stringResource(R.string.import_formats),
                    leading = Icons.Outlined.FileOpen,
                    enabled = importing == 0,
                    onClick = { filePicker.launch(ImportMimeTypes) },
                    trailing = if (importing > 0) {
                        { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.on_this_device),
                    value = pluralStringResource(R.plurals.book_count, localCount, localCount),
                )
            }

            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                HelloWordmark()
            }
            SettingsGroup(stringResource(R.string.about)) {
                SettingsRow(title = stringResource(R.string.app_name), value = stringResource(R.string.version_name, BuildConfig.VERSION_NAME))
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.community),
                    supporting = stringResource(R.string.join_discord),
                    leading = Icons.Outlined.Forum,
                    onClick = { openLink(CommunityLinks.DISCORD_URL) },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.source_code),
                    supporting = stringResource(R.string.source_code_hint),
                    leading = Icons.Outlined.Code,
                    onClick = { openLink(CommunityLinks.GITHUB_URL) },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.privacy_policy),
                    supporting = stringResource(R.string.privacy_policy_hint),
                    leading = Icons.Outlined.PrivacyTip,
                    onClick = { showPrivacyPolicy = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.open_source_licenses),
                    supporting = stringResource(R.string.open_source_licenses_hint),
                    leading = Icons.Outlined.Gavel,
                    onClick = { showLicenses = true },
                )
            }

            Spacer(Modifier.height(Spacing.L))
        }

        if (showPrivacyPolicy) {
            PrivacyPolicySheet(onDismiss = { showPrivacyPolicy = false })
        }
        if (showLicenses) {
            OpenSourceLicensesSheet(onDismiss = { showLicenses = false })
        }
    }
}

internal fun fontAndLayoutSummary(
    settings: ReaderSettings,
    pagesLabel: String,
    scrollLabel: String,
    customFontLabel: String,
): String {
    val layout = if (settings.layoutMode == ReadingLayoutMode.PAGED) pagesLabel else scrollLabel
    return "${settings.fontFamily.chipName(customFontLabel)} · $layout"
}

internal fun formatSpeed(speed: Float): String = LibraryFormatters.speed(speed)

@Composable
private fun SpeedRow(speed: Float, onStep: (Float) -> Unit) {
    SettingsRow(
        title = stringResource(R.string.narration_speed),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onStep(-0.1f) }, enabled = speed > SettingsViewModel.MIN_SPEED) {
                    Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.slower))
                }
                Text(formatSpeed(speed), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { onStep(0.1f) }, enabled = speed < SettingsViewModel.MAX_SPEED) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.faster))
                }
            }
        },
    )
}

@Composable
private fun ServerCardSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(Radius.Card),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.M),
    ) {
        Column(Modifier.padding(start = Spacing.M, end = Spacing.XS, top = Spacing.M, bottom = Spacing.XS)) {
            content()
        }
    }
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

internal fun isRiverSong(profile: ServerProfile): Boolean =
    profile.baseUrl.contains("sweetie", ignoreCase = true) || profile.baseUrl.contains("riversong", ignoreCase = true)

@Composable
private fun ServerCard(
    profile: ServerProfile,
    profiles: List<ServerProfile>,
    onSwitch: (String) -> Unit,
    onAddServer: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var showSwitcher by remember { mutableStateOf(false) }
    ServerCardSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.Dns)
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(LocalHelloColors.current.success)
                    Text(
                        pluralStringResource(R.plurals.connected_servers, profiles.size, profiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.signed_in_as, profile.username),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.S),
        )
        if (isRiverSong(profile)) {
            Text(
                stringResource(R.string.river_song_greeting),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.XXS),
            )
        }
        Row(Modifier.padding(top = Spacing.XXS)) {
            if (profiles.size > 1) {
                Box {
                    TextButton(onClick = { showSwitcher = true }) { Text(stringResource(R.string.switch_server)) }
                    DropdownMenu(expanded = showSwitcher, onDismissRequest = { showSwitcher = false }) {
                        profiles.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.joined_with_dot, p.name, p.username)) },
                                onClick = {
                                    showSwitcher = false
                                    onSwitch(p.id)
                                },
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onAddServer) { Text(stringResource(R.string.add_server)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
        }
    }
}

@Composable
private fun NoServerCard(
    onAddServer: () -> Unit,
    onScanQr: () -> Unit,
    savedProfiles: List<ServerProfile>,
    onResume: (ServerProfile) -> Unit,
) {
    ServerCardSurface {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Outlined.CloudOff)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.not_connected), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier
                .padding(top = Spacing.M, end = Spacing.XS, bottom = Spacing.XS)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
        ) {
            Button(onClick = onAddServer, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.connect_server)) }
            OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.XS))
                Text(stringResource(R.string.scan_code))
            }
        }
        savedProfiles.forEach { saved ->
            TextButton(onClick = { onResume(saved) }) { Text(stringResource(R.string.reconnect_to, saved.name)) }
        }
    }
}

@Composable
private fun ThemePickerRow(
    title: String,
    selectedName: String,
    presets: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            title = title,
            value = presets.firstOrNull { it.first.equals(selectedName, ignoreCase = true) }?.second ?: selectedName,
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            presets.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}
