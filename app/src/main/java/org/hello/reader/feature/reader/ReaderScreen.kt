package org.hello.reader.feature.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.hello.reader.core.util.findActivity
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.reader.SyncedPlaybackState
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.core.model.ReadingLayoutMode
import org.hello.reader.core.model.isLocal
import org.hello.reader.core.util.DeviceProfile
import org.hello.reader.core.util.HardwareKeyDispatcher
import org.hello.reader.feature.library.COVER_ASPECT
import org.hello.reader.feature.library.CoverImage
import org.hello.reader.feature.library.DownloadFormatSheet
import org.hello.reader.feature.library.LibraryFormatters
import org.hello.reader.feature.library.SyncConflictDialog
import org.hello.reader.feature.settings.ReaderSettingsSheet
import org.hello.reader.ui.theme.Spacing
import org.hello.reader.ui.theme.buildColorScheme
import org.hello.reader.ui.theme.parseColorOrNull
import org.hello.reader.ui.theme.toThemeSeed
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.ui.components.Labels
import org.hello.reader.ui.components.rememberLabels

/**
 * Hybrid reader: EPUB text with synchronized narration, highlights, notes,
 * bookmarks and dictionary lookups. Audio-only books show a Now playing screen.
 *
 * The whole reader (chrome and sheets) is themed from the page colors, whatever the app theme.
 *
 * Gestures on the text: tap toggles the reader chrome (in paginated mode the
 * left/right fifths of the screen turn the page instead); double tap starts
 * narration at that sentence; long press selects a word (drag to extend) and
 * opens the selection menu.
 */
@Composable
fun ReaderScreen(
    bookId: String,
    initialAudioOnlyView: Boolean = false,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.factory(bookId),
    ),
) {
    val settings by viewModel.settings.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val resolvedSettings = remember(settings, isSystemDark) {
        settings.withDayNightResolved(isSystemDark)
    }
    val seed = remember(resolvedSettings) { resolvedSettings.toThemeSeed() }
    val pageScheme = remember(seed) { buildColorScheme(seed) }
    MaterialTheme(colorScheme = pageScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        ReaderContent(
            viewModel = viewModel,
            settings = resolvedSettings,
            initialAudioOnlyView = initialAudioOnlyView,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    viewModel: ReaderViewModel,
    settings: ReaderSettings,
    initialAudioOnlyView: Boolean = false,
    onBack: () -> Unit,
) {
    val mode by viewModel.readerMode.collectAsState()
    val sessionLoaded by viewModel.sessionLoaded.collectAsState()
    val sentences by viewModel.sentences.collectAsState()
    val sleepRemainingMs by viewModel.sleepTimerRemainingMs.collectAsState()
    val conflict by viewModel.activeConflict.collectAsState()
    val annotations by viewModel.annotations.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val dictionary by viewModel.dictionary.collectAsState()
    val sessionError by viewModel.sessionError.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val readingAnchor by viewModel.readingAnchor.collectAsState()
    val sleepAtChapterEnd by viewModel.sleepAtChapterEnd.collectAsState()
    val resumedElementId by viewModel.resumedElementId.collectAsState()
    val resumedAudioNotice by viewModel.resumedAudioNotice.collectAsState()
    val isFollowingNarration by viewModel.isFollowingNarration.collectAsState()

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showNotebook by rememberSaveable { mutableStateOf(false) }
    var showSearchSheet by rememberSaveable { mutableStateOf(false) }
    var dismissedConflict by remember { mutableStateOf(false) }
    var selectionAnchor by remember { mutableStateOf<Rect?>(null) }
    var visibleElementId by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pagesSinceRefresh by rememberSaveable { mutableIntStateOf(0) }
    var flashPhase by remember { mutableStateOf<FullRefreshPhase?>(null) }
    var isActiveElementVisible by remember { mutableStateOf(true) }
    var spreadGutter by remember { mutableStateOf<Dp?>(null) }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.copied)
    val labels = rememberLabels()

    val triggerFullRefresh: () -> Unit = {
        scope.launch {
            flashPhase = FullRefreshPhase.BLACK
            withFrameNanos { }
            delay(FULL_REFRESH_PHASE_MS)
            flashPhase = FullRefreshPhase.WHITE
            withFrameNanos { }
            delay(FULL_REFRESH_PHASE_MS)
            flashPhase = null
        }
    }

    val colors = MaterialTheme.colorScheme
    val bgColor = colors.background
    val fgColor = colors.onBackground
    val narrationColor = parseColorOrNull(settings.effectiveHighlightHex) ?: colors.primaryContainer
    val isDarkPage = bgColor.luminance() < 0.4f

    val currentBook = when (val m = mode) {
        is ReaderMode.ReadaloudSynced -> m.book
        is ReaderMode.ReadingOnly -> m.book
        is ReaderMode.ListeningOnly -> m.book
        is ReaderMode.NotDownloaded -> m.book
        else -> null
    }
    var showAudioOnlyView by rememberSaveable { mutableStateOf(initialAudioOnlyView) }
    var showDownloadFormatSheet by rememberSaveable { mutableStateOf(false) }
    val isListeningOnly = mode is ReaderMode.ListeningOnly
    val isAudioOnly = isListeningOnly || (mode is ReaderMode.ReadaloudSynced && showAudioOnlyView)
    ImmersiveMode(enabled = !chromeVisible && !isAudioOnly)
    ReaderBrightnessController(settings = settings)

    // Synced narration highlight. Silent reading has no active narration highlight.
    val activeElementId = when (val m = mode) {
        is ReaderMode.ReadaloudSynced -> m.highlight?.elementId
        else -> null
    }
    val currentChapter = when (val m = mode) {
        is ReaderMode.ReadaloudSynced -> m.chapterIndex
        is ReaderMode.ReadingOnly -> m.chapterIndex
        is ReaderMode.ListeningOnly -> m.chapterIndex
        else -> null
    }?.takeIf { chapters.size > 0 }

    val decorations = remember(annotations, selection, isDarkPage, colors.primary) {
        ParagraphDecorations(
            marks = highlightMarks(annotations, isDarkPage),
            bookmarked = annotations.filter { it.type == AnnotationType.BOOKMARK }.mapNotNull { it.elementId }.toSet(),
            selection = selection,
            selectionColor = colors.primary.copy(alpha = if (isDarkPage) 0.40f else 0.24f),
        )
    }

    val foldableInfo = rememberFoldableLayoutInfo()

    val gestures = remember(viewModel) {
        object : ReaderTextGestures {
            override fun onTap(positionInWindow: Offset) {
                if (viewModel.selection.value != null) {
                    viewModel.clearSelection()
                    selectionAnchor = null
                    return
                }
                chromeVisible = !chromeVisible
            }

            override fun onDoubleTap(sentence: ReaderSentenceItem) = viewModel.onSentenceTapped(sentence)

            override fun onSelectionStart(sentence: ReaderSentenceItem, offset: Int) {
                selectionAnchor = null
                viewModel.startSelection(sentence, offset)
            }

            override fun onSelectionExtend(sentence: ReaderSentenceItem, offset: Int) =
                viewModel.extendSelection(sentence, offset)

            override fun onSelectionEnd(sentence: ReaderSentenceItem, anchorInWindow: Rect) {
                selectionAnchor = anchorInWindow
            }
        }
    }

    val activeConflict = conflict
    if (activeConflict != null && !dismissedConflict) {
        SyncConflictDialog(
            conflict = activeConflict,
            bookTitle = currentBook?.title,
            onKeepLocal = { viewModel.resolveConflict(useRemote = false) },
            onUseRemote = { viewModel.resolveConflict(useRemote = true) },
            onDismiss = { dismissedConflict = true },
        )
    }

    val status = readerStatus(mode, settings.layoutMode, page, pageCount, labels, chapterRemainingMs(chapters, mode))
    val audio = audioControlsFor(mode, sleepRemainingMs, sleepAtChapterEnd, chapters, settings, viewModel)
        ?.let { controls -> if (chapters.isNavigable) controls.copy(onOpenChapters = { showNotebook = true }) else controls }

    val isAudioPlaying = audio?.isPlaying == true
    var autoPlayAttempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sessionLoaded, initialAudioOnlyView) {
        if (initialAudioOnlyView && sessionLoaded && !autoPlayAttempted) {
            autoPlayAttempted = true
            if (audio?.isPlaying != true) {
                viewModel.play()
            }
        }
    }
    val hasActiveOverlay = showSettingsSheet ||
        showNotebook ||
        showDownloadFormatSheet ||
        (activeConflict != null && !dismissedConflict) ||
        dictionary != null
    val isPagedMode = settings.layoutMode == ReadingLayoutMode.PAGED

    LaunchedEffect(resumedAudioNotice) {
        val notice = resumedAudioNotice
        if (notice != null) {
            val chapter = notice.chapterTitle ?: context.getString(R.string.chapter_number, notice.chapterIndex + 1)
            val time = LibraryFormatters.position(notice.positionMs)
            val text = context.getString(R.string.resumed_at_audio_notice, chapter, time)
            snackbar.showSnackbar(text)
            viewModel.clearResumeHighlight()
        }
    }

    DisposableEffect(Unit) {
        HardwareKeyDispatcher.isReaderActive = true
        onDispose {
            HardwareKeyDispatcher.isReaderActive = false
        }
    }

    SideEffect {
        HardwareKeyDispatcher.volumeKeysTurnPages = settings.volumeButtonsTurnPages
        HardwareKeyDispatcher.isAudioPlaying = isAudioPlaying
        HardwareKeyDispatcher.isAudioOnlyMode = isAudioOnly
        HardwareKeyDispatcher.isOverlayOpen = hasActiveOverlay
        HardwareKeyDispatcher.isChromeVisible = chromeVisible
        HardwareKeyDispatcher.isPagedMode = isPagedMode
    }

    val renderCanvas: @Composable (Modifier, String?) -> Unit = { canvasModifier, forcedActiveElementId ->
        ReaderCanvas(
            sentences = sentences,
            layoutMode = settings.layoutMode,
            style = readerTextStyle(settings, fgColor, rememberReaderFont(settings).family),
            paragraphSpacingPx = with(LocalDensity.current) { (12f * settings.paragraphSpacingEm).dp.toPx() },
            horizontalMarginPx = with(LocalDensity.current) { settings.marginHorizontalDp.dp.toPx() },
            activeElementId = forcedActiveElementId,
            initialAnchorElementId = readingAnchor,
            activeColor = narrationColor,
            decorations = decorations,
            gestures = gestures,
            scrollRequests = viewModel.scrollRequests,
            onVisibleElementChanged = {
                visibleElementId = it
                viewModel.onVisibleElementChanged(it)
            },
            onPageChanged = { current, total ->
                if (current != page) {
                    val delta = kotlin.math.abs(current - page)
                    page = current
                    pageCount = total
                    val interval = settings.fullRefreshIntervalPages
                    if (interval > 0) {
                        pagesSinceRefresh += delta
                        if (pagesSinceRefresh >= interval) {
                            pagesSinceRefresh = 0
                            triggerFullRefresh()
                        }
                    }
                } else {
                    pageCount = total
                }
            },
            pageTurnAnimation = settings.pageTurnAnimation,
            eInkOptimized = settings.eInkOptimized,
            twoPageViewMode = settings.twoPageViewMode,
            resumedElementId = resumedElementId,
            onClearResumeHighlight = viewModel::clearResumeHighlight,
            onActiveElementVisible = { isActiveElementVisible = it },
            onSpreadGutterChanged = { spreadGutter = it },
            isFollowingNarration = isFollowingNarration,
            onFollowingNarrationChanged = viewModel::setFollowingNarration,
            modifier = canvasModifier,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        // The spine of a two-page spread runs the full height of the screen, through the system bar
        // areas the pages leave empty; the pages draw the same spine over their own area.
        val gutter = spreadGutter
        val showsSpread = mode is ReaderMode.ReadingOnly || (mode is ReaderMode.ReadaloudSynced && !showAudioOnlyView)
        if (gutter != null && showsSpread && !foldableInfo.isTabletop) {
            SpreadCreaseSpine(
                foldable = foldableInfo,
                gutterDp = gutter,
                isDark = bgColor.luminance() < 0.5f,
                isEInk = settings.eInkOptimized || DeviceProfile.isCurrentDeviceEInk,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Insets that ignore visibility keep the text from reflowing when the chrome hides the system bars.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
            val error = sessionError
            when (val m = mode) {
                is ReaderMode.Idle, is ReaderMode.Loading -> if (error != null) {
                    OpenBookError(
                        detail = error,
                        onRetry = viewModel::retryOpen,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.primary)
                }
                is ReaderMode.NotDownloaded -> {
                    NotDownloadedContent(
                        book = m.book,
                        onDownload = {
                            if (m.book.isLocal) {
                                viewModel.downloadBook()
                            } else {
                                val available = m.book.availableFormats()
                                if (available.size > 1) {
                                    showDownloadFormatSheet = true
                                } else {
                                    viewModel.downloadBook(available.firstOrNull() ?: m.book.mediaType)
                                }
                            }
                        },
                        onCancel = viewModel::cancelDownload,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                is ReaderMode.ListeningOnly -> audio?.let { controls ->
                    NowPlayingContent(
                        mode = m,
                        controls = controls,
                        modifier = Modifier.padding(top = ReaderTopBarHeight),
                    )
                }
                is ReaderMode.ReadaloudSynced -> {
                    if (showAudioOnlyView) {
                        audio?.let { controls ->
                            NowPlayingContent(
                                book = m.book,
                                chapterIndex = m.chapterIndex,
                                chapterTitle = m.chapterTitle,
                                controls = controls,
                                onSwitchToReading = { showAudioOnlyView = false },
                                modifier = Modifier.padding(top = ReaderTopBarHeight),
                            )
                        }
                    } else if (foldableInfo.isTabletop) {
                        TabletopSplitLayout(
                            foldingFeature = foldableInfo.foldingFeature,
                            topContent = {
                                Box(Modifier.fillMaxSize()) {
                                    renderCanvas(Modifier.fillMaxSize(), activeElementId)
                                    val isPagedAwayFromNarration = mode is ReaderMode.ReadaloudSynced && activeElementId != null && !isActiveElementVisible
                                    val showTabletopPill = (chromeVisible || !isFollowingNarration || settings.alwaysShowBackToReadingPosition) &&
                                        isPagedAwayFromNarration
                                    if (settings.eInkOptimized) {
                                        if (showTabletopPill) {
                                            BackToReadingPill(
                                                onClick = viewModel::jumpToActiveNarration,
                                                modifier = Modifier
                                                    .align(Alignment.BottomCenter)
                                                    .padding(bottom = Spacing.M),
                                            )
                                        }
                                    } else {
                                        AnimatedVisibility(
                                            visible = showTabletopPill,
                                            enter = fadeIn() + slideInVertically { it },
                                            exit = fadeOut() + slideOutVertically { it },
                                            modifier = Modifier.align(Alignment.BottomCenter),
                                        ) {
                                            BackToReadingPill(
                                                onClick = viewModel::jumpToActiveNarration,
                                                modifier = Modifier.padding(bottom = Spacing.M),
                                            )
                                        }
                                    }
                                }
                            },
                            bottomContent = {
                                TabletopAudioControls(
                                    status = status,
                                    audio = audio,
                                    bookTitle = currentBook?.title,
                                    chapterTitle = status.chapter,
                                    onListen = null,
                                    onToggleAudioOnlyView = { showAudioOnlyView = true },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = if (chromeVisible) ReaderTopBarHeight else 0.dp),
                        )
                    } else {
                        renderCanvas(Modifier.fillMaxSize(), activeElementId)
                        AnimatedVisibility(
                            visible = !chromeVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            QuietFooter(status)
                        }
                    }
                }
                is ReaderMode.ReadingOnly -> {
                    if (foldableInfo.isTabletop) {
                        TabletopSplitLayout(
                            foldingFeature = foldableInfo.foldingFeature,
                            topContent = { renderCanvas(Modifier.fillMaxSize(), null) },
                            bottomContent = {
                                TabletopAudioControls(
                                    status = status,
                                    audio = audio,
                                    bookTitle = currentBook?.title,
                                    chapterTitle = status.chapter,
                                    onListen = if (currentBook?.mediaType == BookMediaType.READALOUD) {
                                        viewModel::toggleSynchronizedNarration
                                    } else null,
                                    onToggleAudioOnlyView = null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = if (chromeVisible) ReaderTopBarHeight else 0.dp),
                        )
                    } else {
                        renderCanvas(Modifier.fillMaxSize(), null)
                        AnimatedVisibility(
                            visible = !chromeVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            QuietFooter(status)
                        }
                    }
                }
            }
        }

        if (!chromeVisible && !isAudioOnly && !foldableInfo.isTabletop && mode !is ReaderMode.Loading && mode !is ReaderMode.Idle) {
            QuietProgressLine(status.progress, Modifier.align(Alignment.BottomStart))
        }

        val showFloatingPill = !chromeVisible && !isAudioOnly && !foldableInfo.isTabletop &&
            (!isFollowingNarration || settings.alwaysShowBackToReadingPosition) &&
            mode is ReaderMode.ReadaloudSynced &&
            activeElementId != null && !isActiveElementVisible

        val floatingPillBottomPadding = 48.dp

        if (settings.eInkOptimized) {
            if (showFloatingPill) {
                BackToReadingPill(
                    onClick = viewModel::jumpToActiveNarration,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = floatingPillBottomPadding),
                )
            }
        } else {
            AnimatedVisibility(
                visible = showFloatingPill,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            ) {
                BackToReadingPill(
                    onClick = viewModel::jumpToActiveNarration,
                    modifier = Modifier.padding(bottom = floatingPillBottomPadding),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible || isAudioOnly,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val isReadaloud = currentBook?.mediaType == BookMediaType.READALOUD
            ReaderTopBar(
                title = currentBook?.title ?: stringResource(R.string.loading),
                subtitle = status.chapter,
                listening = if (isReadaloud) null else if (currentBook?.mediaType == BookMediaType.AUDIO_ONLY) true else null,
                isBookmarked = visibleElementId?.let { id -> decorations.bookmarked.contains(id) } == true,
                canBookmark = visibleElementId != null && !isAudioOnly,
                showTextTools = !isAudioOnly,
                onBack = onBack,
                onToggleListening = viewModel::toggleSynchronizedNarration,
                onToggleBookmark = {
                    sentences.firstOrNull { it.elementId == visibleElementId }?.let(viewModel::toggleBookmark)
                },
                onOpenNotebook = { showNotebook = true },
                onOpenSettings = { showSettingsSheet = true },
                onOpenSearch = { showSearchSheet = true },
                onToggleAudioOnlyView = if (isReadaloud) {
                    { showAudioOnlyView = !showAudioOnlyView }
                } else null,
                isAudioOnlyView = showAudioOnlyView,
            )
        }

        if (!isAudioOnly && !foldableInfo.isTabletop) {
            AnimatedVisibility(
                visible = chromeVisible && (mode is ReaderMode.ReadingOnly || mode is ReaderMode.ReadaloudSynced),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val m = mode
                Column(
                    modifier = Modifier.navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val showChromePill = m is ReaderMode.ReadaloudSynced && activeElementId != null && !isActiveElementVisible
                    if (settings.eInkOptimized) {
                        if (showChromePill) {
                            BackToReadingPill(
                                onClick = viewModel::jumpToActiveNarration,
                                modifier = Modifier.padding(bottom = Spacing.S),
                            )
                        }
                    } else {
                        AnimatedVisibility(
                            visible = showChromePill,
                            enter = fadeIn() + slideInVertically { it },
                            exit = fadeOut() + slideOutVertically { it },
                        ) {
                            BackToReadingPill(
                                onClick = viewModel::jumpToActiveNarration,
                                modifier = Modifier.padding(bottom = Spacing.S),
                            )
                        }
                    }
                    if (m is ReaderMode.ReadaloudSynced && audio != null && currentBook != null) {
                        ReaderMiniPlayerCard(
                            book = currentBook,
                            status = status,
                            audio = audio,
                            onOpenAudioOnlyView = { showAudioOnlyView = true },
                            isEInk = settings.eInkOptimized || org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk,
                        )
                    } else {
                        ReaderToolbar(
                            status = status,
                            audio = audio,
                            onListen = if (m is ReaderMode.ReadingOnly && m.book.mediaType == BookMediaType.READALOUD) {
                                viewModel::toggleSynchronizedNarration
                            } else {
                                null
                            },
                            onToggleAudioOnlyView = if (m is ReaderMode.ReadaloudSynced) {
                                { showAudioOnlyView = true }
                            } else null,
                        )
                    }
                }
            }
        }

        if (showDownloadFormatSheet && mode is ReaderMode.NotDownloaded) {
            DownloadFormatSheet(
                book = (mode as ReaderMode.NotDownloaded).book,
                onSelectFormat = { format ->
                    viewModel.downloadBook(format)
                    showDownloadFormatSheet = false
                },
                onDismiss = { showDownloadFormatSheet = false },
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        flashPhase?.let { phase ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (phase == FullRefreshPhase.BLACK) Color.Black else Color.White),
            )
        }
    }

    val currentSelection = selection
    val anchor = selectionAnchor
    if (currentSelection != null && anchor != null) {
        SelectionMenu(
            selection = currentSelection,
            anchorInWindow = anchor,
            canPlayHere = currentBook?.mediaType == BookMediaType.READALOUD,
            actions = object : SelectionActions {
                override fun onDefine(word: String) {
                    selectionAnchor = null
                    viewModel.define(word)
                }

                override fun onHighlight(color: HighlightColor) {
                    selectionAnchor = null
                    viewModel.highlightSelection(color)
                }

                override fun onAddNote(note: String) {
                    selectionAnchor = null
                    viewModel.highlightSelection(HighlightColor.YELLOW, note)
                }

                override fun onBookmark() {
                    selectionAnchor = null
                    viewModel.toggleBookmark(currentSelection.sentence)
                    viewModel.clearSelection()
                }

                override fun onCopy(text: String) {
                    selectionAnchor = null
                    clipboard.setText(AnnotatedString(text))
                    viewModel.clearSelection()
                    // Android 13+ shows its own clipboard confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        scope.launch { snackbar.showSnackbar(copiedMessage) }
                    }
                }

                override fun onPlayHere() {
                    selectionAnchor = null
                    val sentence = currentSelection.sentence
                    viewModel.clearSelection()
                    // Also switches a read-only readaloud into synchronized narration.
                    viewModel.onSentenceTapped(sentence)
                }

                override fun onDismiss() {
                    selectionAnchor = null
                    viewModel.clearSelection()
                }
            },
        )
    }

    dictionary?.let { state ->
        DictionaryBottomSheet(state = state, onRetry = viewModel::define, onDismiss = viewModel::dismissDictionary)
    }

    if (showNotebook) {
        AnnotationsSheet(
            annotations = annotations,
            paragraphOrder = remember(sentences) { sentences.withIndex().associate { it.value.elementId to it.index } },
            onJumpTo = { annotation ->
                showNotebook = false
                viewModel.jumpTo(annotation)
            },
            onEditNote = viewModel::editNote,
            onDelete = viewModel::deleteAnnotation,
            onExport = {
                val markdown = viewModel.exportMarkdown() ?: return@AnnotationsSheet
                context.shareMarkdown(currentBook?.title ?: context.getString(R.string.export_notes_untitled), markdown)
            },
            onDismiss = { showNotebook = false },
            chapters = chapters,
            currentChapter = currentChapter,
            onGoToChapter = { index ->
                showNotebook = false
                viewModel.goToChapter(index)
            },
        )
    }

    if (showSettingsSheet) {
        ReaderSettingsSheet(
            settings = settings,
            onUpdateSettings = viewModel::updateSettings,
            onDismiss = { showSettingsSheet = false },
        )
    }

    if (showSearchSheet) {
        val searchQuery by viewModel.searchQuery.collectAsState()
        val searchState by viewModel.searchResults.collectAsState()
        BookSearchSheet(
            query = searchQuery,
            searchState = searchState,
            onQueryChange = viewModel::updateSearchQuery,
            onClearQuery = viewModel::clearSearchQuery,
            onSelectResult = { elementId ->
                showSearchSheet = false
                viewModel.jumpToSearchResult(elementId)
            },
            onDismiss = {
                showSearchSheet = false
                viewModel.clearSearchQuery()
            },
        )
    }
}

/** Audio state and actions for the toolbar and Now playing, or null when the mode has no audio running. */
private fun audioControlsFor(
    mode: ReaderMode,
    sleepRemainingMs: Long?,
    sleepAtChapterEnd: Boolean,
    chapters: BookChapters,
    settings: ReaderSettings,
    viewModel: ReaderViewModel,
): AudioControls? {
    val (playing, position, duration, speed) = when (mode) {
        is ReaderMode.ReadaloudSynced -> AudioSnapshot(
            mode.playbackState == SyncedPlaybackState.PLAYING, mode.audioPositionMs, mode.totalDurationMs, mode.playbackSpeed,
        )
        is ReaderMode.ListeningOnly -> AudioSnapshot(mode.isPlaying, mode.audioPositionMs, mode.totalDurationMs, mode.playbackSpeed)
        else -> return null
    }
    return AudioControls(
        isPlaying = playing,
        positionMs = position,
        durationMs = duration,
        speed = speed,
        sleepRemainingMs = sleepRemainingMs,
        skipBackSeconds = settings.skipBackSeconds.coerceIn(5, 120),
        skipForwardSeconds = settings.skipForwardSeconds.coerceIn(5, 120),
        onTogglePlayPause = viewModel::togglePlayPause,
        onSeekTo = viewModel::seekToMs,
        onSkipBy = viewModel::skipByMs,
        onSpeedChange = viewModel::setPlaybackSpeed,
        onStartSleepTimer = viewModel::startSleepTimer,
        onCancelSleepTimer = viewModel::cancelSleepTimer,
        chapterTicks = chapters.tickFractions(duration),
        chapterRemainingMs = chapterRemainingMs(chapters, mode),
        onPreviousChapter = if (chapters.hasTiming && chapters.isNavigable) viewModel::previousChapter else null,
        onNextChapter = if (chapters.hasTiming && chapters.isNavigable) viewModel::nextChapter else null,
        sleepAtChapterEnd = sleepAtChapterEnd,
        onSleepAtChapterEnd = if (chapters.hasTiming) viewModel::sleepAtEndOfChapter else null,
    )
}

/** Narration left before the next chapter starts (or the current one's last sentence ends). */
internal fun chapterRemainingMs(chapters: BookChapters, mode: ReaderMode): Long? {
    val position = when (mode) {
        is ReaderMode.ReadaloudSynced -> mode.audioPositionMs
        is ReaderMode.ListeningOnly -> mode.audioPositionMs
        else -> return null
    }
    val index = chapters.indexAt(position) ?: return null
    val end = chapters[index + 1]?.startMs ?: chapters[index]?.endMs ?: return null
    return (end - position).coerceAtLeast(0L)
}

private data class AudioSnapshot(val playing: Boolean, val position: Long, val duration: Long, val speed: Float)

/** Progress and labels shared by the toolbar, the quiet footer and the top bar subtitle. */
internal data class ReaderStatus(
    val chapter: String,
    /** 0..1 */
    val progress: Float,
    val percentLabel: String,
    /** "2h 05m left" for audio, "Page 12 of 40" when paginated, else empty. */
    val detail: String,
    val pageLabel: String = "",
)

internal fun readerStatus(
    mode: ReaderMode,
    layout: ReadingLayoutMode,
    page: Int,
    pageCount: Int,
    labels: Labels,
    chapterRemainingMs: Long? = null,
): ReaderStatus {
    fun audioLeft(total: Long, position: Long) =
        chapterRemainingMs?.let(labels::timeLeftInChapter) ?: labels.timeLeft((total - position).coerceAtLeast(0L))
    fun chapterName(index: Int, title: String?) = labels.chapter(index, title)
    val pageLabel = if (layout == ReadingLayoutMode.PAGED && pageCount > 0) labels.page(page, pageCount) else ""

    return when (mode) {
        is ReaderMode.ReadaloudSynced -> ReaderStatus(
            chapter = chapterName(mode.chapterIndex, mode.chapterTitle),
            progress = (mode.progressPercent / 100f).coerceIn(0f, 1f),
            percentLabel = LibraryFormatters.percent((mode.progressPercent / 100f).coerceIn(0f, 1f)),
            detail = audioLeft(mode.totalDurationMs, mode.audioPositionMs),
            pageLabel = pageLabel,
        )
        is ReaderMode.ListeningOnly -> ReaderStatus(
            chapter = chapterName(mode.chapterIndex, mode.chapterTitle),
            progress = (mode.progressPercent / 100f).coerceIn(0f, 1f),
            percentLabel = LibraryFormatters.percent((mode.progressPercent / 100f).coerceIn(0f, 1f)),
            detail = audioLeft(mode.totalDurationMs, mode.audioPositionMs),
            pageLabel = "",
        )
        is ReaderMode.ReadingOnly -> ReaderStatus(
            chapter = chapterName(mode.chapterIndex, mode.chapterTitle),
            progress = (mode.progressPercent / 100f).coerceIn(0f, 1f),
            percentLabel = LibraryFormatters.percent((mode.progressPercent / 100f).coerceIn(0f, 1f)),
            detail = pageLabel,
            pageLabel = pageLabel,
        )
        else -> ReaderStatus(chapter = "", progress = 0f, percentLabel = "", detail = "", pageLabel = "")
    }
}

/** Saved highlights per paragraph. Pastel highlight colors are toned down on dark pages to keep text legible. */
internal fun highlightMarks(annotations: List<BookAnnotation>, darkPage: Boolean): Map<String, List<TextMark>> =
    annotations
        .filter { it.type == AnnotationType.HIGHLIGHT && it.elementId != null && it.endOffset > it.startOffset }
        .groupBy { it.elementId!! }
        .mapValues { (_, list) ->
            list.map { annotation ->
                val base = parseColorOrNull(annotation.color.hex) ?: Color.Yellow
                TextMark(
                    CharSpan(annotation.startOffset, annotation.endOffset),
                    if (darkPage) base.copy(alpha = 0.35f) else base,
                )
            }
        }

/** `boldWeight` 0..3 maps to Regular, Medium, SemiBold, Bold. */
internal fun readerTextStyle(settings: ReaderSettings, color: Color, fontFamily: androidx.compose.ui.text.font.FontFamily): TextStyle {
    val fontSize = (18f * (settings.fontScalePercent / 100f)).sp
    return TextStyle(
        color = color,
        fontFamily = fontFamily,
        fontSize = fontSize,
        lineHeight = (fontSize.value * settings.lineHeightMultiplier).sp,
        letterSpacing = settings.letterSpacingEm.em,
        fontWeight = FontWeight(400 + 100 * settings.boldWeight.coerceIn(0, 3)),
        textAlign = if (settings.textJustified) TextAlign.Justify else TextAlign.Start,
        hyphens = if (settings.textJustified) Hyphens.Auto else Hyphens.None,
        lineBreak = if (settings.textJustified) LineBreak.Paragraph else LineBreak.Simple,
    )
}

/** Hides the status and navigation bars while [enabled]; swiping from an edge reveals them briefly. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Overrides window brightness while the reader is displayed, restoring system default on exit.
 * On E-ink devices, frontlight brightness is system-managed and not overridden.
 */
@Composable
private fun ReaderBrightnessController(settings: ReaderSettings) {
    val view = LocalView.current
    val isEInk = org.hello.reader.core.util.DeviceProfile.isCurrentDeviceEInk || settings.eInkOptimized
    DisposableEffect(settings.brightnessFollowSystem, settings.brightnessPercent, isEInk, view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose { }
        if (!isEInk) {
            val lp = window.attributes
            val targetBrightness = if (settings.brightnessFollowSystem) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                (settings.brightnessPercent.coerceIn(1, 100) / 100f).coerceIn(0.01f, 1f)
            }
            if (lp.screenBrightness != targetBrightness) {
                lp.screenBrightness = targetBrightness
                window.attributes = lp
            }
        }
        onDispose {
            val lp = window.attributes
            if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }
}

private fun Context.shareMarkdown(title: String, markdown: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_notes_subject, title))
        putExtra(Intent.EXTRA_TEXT, markdown)
    }
    startActivity(Intent.createChooser(send, getString(R.string.export_notes)))
}

@Composable
private fun OpenBookError(
    detail: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        Text(stringResource(R.string.reader_open_failed_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.reader_open_failed_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // The technical reason, small, for bug reports.
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(
            Modifier.padding(top = Spacing.XS),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.back_to_library)) }
            Button(onClick = onRetry, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.try_again)) }
        }
    }
}

@Composable
private fun NotDownloadedContent(
    book: Book,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.S),
    ) {
        CoverImage(
            book = book,
            shape = RoundedCornerShape(6.dp),
            titleSize = 16.sp,
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(COVER_ASPECT)
                .shadow(8.dp, RoundedCornerShape(6.dp), clip = false),
        )
        Text(
            book.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.XS),
        )
        val authors = book.authors.joinToString().ifBlank { null }
        if (authors != null) {
            Text(
                authors,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val isDownloading = book.downloadStatus == DownloadStatus.DOWNLOADING
        val isQueued = book.downloadStatus == DownloadStatus.QUEUED
        if (isDownloading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.L, vertical = Spacing.XS),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.XS),
            ) {
                LinearProgressIndicator(
                    progress = { book.downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Text(
                    stringResource(R.string.reader_downloading_progress, (book.downloadProgress * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (isQueued) {
            Text(
                stringResource(R.string.reader_download_queued),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                stringResource(R.string.reader_not_downloaded_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Row(
            Modifier.padding(top = Spacing.XS),
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.back_to_library))
            }
            if (isDownloading || isQueued) {
                Button(onClick = onCancel, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.reader_cancel_download_action))
                }
            } else {
                Button(onClick = onDownload, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.reader_download_action))
                }
            }
        }
    }
}

internal data class TabletopSplitHeightsPx(
    val topHeightPx: Int,
    val hingeHeightPx: Int,
)

internal fun computeTabletopSplitHeightsPx(
    foldTopInWindow: Int,
    foldBottomInWindow: Int,
    layoutWindowY: Int,
    totalHeightPx: Int,
): TabletopSplitHeightsPx? {
    val localFoldTop = foldTopInWindow - layoutWindowY
    val localFoldBottom = foldBottomInWindow - layoutWindowY
    if (localFoldTop <= 0 || localFoldBottom >= totalHeightPx || localFoldBottom < localFoldTop) {
        return null
    }
    return TabletopSplitHeightsPx(
        topHeightPx = localFoldTop,
        hingeHeightPx = (localFoldBottom - localFoldTop).coerceAtLeast(0),
    )
}

@Composable
internal fun TabletopSplitLayout(
    foldingFeature: androidx.window.layout.FoldingFeature?,
    topContent: @Composable () -> Unit,
    bottomContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var layoutWindowY by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier.onGloballyPositioned { coords ->
            layoutWindowY = coords.positionInWindow().y.roundToInt()
        },
    ) {
        val totalHeightPx = constraints.maxHeight
        val density = LocalDensity.current
        val fold = foldingFeature
        val split = if (fold != null) {
            computeTabletopSplitHeightsPx(
                foldTopInWindow = fold.bounds.top,
                foldBottomInWindow = fold.bounds.bottom,
                layoutWindowY = layoutWindowY,
                totalHeightPx = totalHeightPx,
            )
        } else {
            null
        }
        if (split != null) {
            val topHeightDp = with(density) { split.topHeightPx.toDp() }
            val hingeHeightDp = with(density) { split.hingeHeightPx.toDp() }
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(topHeightDp),
                ) {
                    topContent()
                }
                if (hingeHeightDp > 0.dp) {
                    Spacer(Modifier.height(hingeHeightDp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    bottomContent()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    topContent()
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    bottomContent()
                }
            }
        }
    }
}

@Composable
private fun BackToReadingPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.M, vertical = Spacing.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Headphones,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.XS))
            Text(
                text = stringResource(R.string.return_to_reading_position),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val FULL_REFRESH_PHASE_MS = 150L

private enum class FullRefreshPhase {
    BLACK,
    WHITE,
}
