package org.hello.reader.feature.reader

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.hello.reader.core.engine.smil.isChapterHeadingText
import org.hello.reader.core.model.PageTurnAnimation
import org.hello.reader.core.model.ReadingLayoutMode
import org.hello.reader.core.model.TwoPageViewMode
import org.hello.reader.core.util.DeviceProfile
import org.hello.reader.core.util.HardwareKeyDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the canvas needs to draw one paragraph's decorations. */
data class ParagraphDecorations(
    val marks: Map<String, List<TextMark>>,
    val bookmarked: Set<String>,
    val selection: ReaderSelection?,
    val selectionColor: Color,
)

/** Horizontal padding [ReaderTextBlock] adds on each side; subtracted when measuring pages. */
private val BlockHorizontalPadding = 10.dp

/** Room above the text, and below it for the reader's footer line. */
internal val PageTopPadding = 20.dp
internal val PageBottomPadding = 40.dp

internal fun isSentenceResumed(elementId: String, resumedElementId: String?): Boolean {
    if (resumedElementId == null) return false
    return elementId == resumedElementId || elementId.startsWith(resumedElementId) || resumedElementId.startsWith(elementId)
}

/**
 * Renders the book either as one continuous scroll or as swipeable pages.
 * Narration is followed in both modes: when the active sentence leaves the
 * screen, the canvas scrolls (or turns the page) to it. [scrollRequests] bring
 * an element into view on demand (jump-to from the notebook).
 * [onVisibleElementChanged] reports the first paragraph on screen, which the
 * top bar uses for its bookmark toggle.
 */
@Composable
fun ReaderCanvas(
    sentences: List<ReaderSentenceItem>,
    layoutMode: ReadingLayoutMode,
    style: TextStyle,
    paragraphSpacingPx: Float,
    horizontalMarginPx: Float,
    activeElementId: String?,
    activeColor: Color,
    decorations: ParagraphDecorations,
    gestures: ReaderTextGestures,
    scrollRequests: SharedFlow<ReaderScrollTarget>,
    onVisibleElementChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
    initialAnchorElementId: String? = null,
    onPageChanged: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
    pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.CURL,
    eInkOptimized: Boolean = false,
    twoPageViewMode: TwoPageViewMode = TwoPageViewMode.AUTO,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    resumedElementId: String? = null,
    onClearResumeHighlight: () -> Unit = {},
    onActiveElementVisible: (Boolean) -> Unit = {},
    /** The gutter between the two pages of a spread, or null when a single page or scroll is shown. */
    onSpreadGutterChanged: (Dp?) -> Unit = {},
    isFollowingNarration: Boolean = true,
    onFollowingNarrationChanged: (Boolean) -> Unit = {},
) {
    val currentOnSpreadGutter by rememberUpdatedState(onSpreadGutterChanged)
    if (layoutMode != ReadingLayoutMode.PAGED) {
        LaunchedEffect(Unit) { currentOnSpreadGutter(null) }
    }
    when (layoutMode) {
        ReadingLayoutMode.CONTINUOUS_SCROLL -> ContinuousCanvas(
            sentences, style, paragraphSpacingPx, horizontalMarginPx, activeElementId, initialAnchorElementId, activeColor,
            decorations, gestures, scrollRequests, onVisibleElementChanged, modifier, resumedElementId, eInkOptimized, onClearResumeHighlight,
            onActiveElementVisible, isFollowingNarration, onFollowingNarrationChanged,
        )
        ReadingLayoutMode.PAGED -> PagedCanvas(
            sentences, style, paragraphSpacingPx, horizontalMarginPx, activeElementId, initialAnchorElementId, activeColor,
            decorations, gestures, scrollRequests, onVisibleElementChanged, onPageChanged, pageTurnAnimation, eInkOptimized, twoPageViewMode, backgroundColor, modifier,
            resumedElementId, onClearResumeHighlight, onActiveElementVisible, currentOnSpreadGutter,
            isFollowingNarration, onFollowingNarrationChanged,
        )
    }
}

@Composable
private fun ContinuousCanvas(
    sentences: List<ReaderSentenceItem>,
    style: TextStyle,
    paragraphSpacingPx: Float,
    horizontalMarginPx: Float,
    activeElementId: String?,
    initialAnchorElementId: String?,
    activeColor: Color,
    decorations: ParagraphDecorations,
    gestures: ReaderTextGestures,
    scrollRequests: SharedFlow<ReaderScrollTarget>,
    onVisibleElementChanged: (String?) -> Unit,
    modifier: Modifier,
    resumedElementId: String?,
    eInkOptimized: Boolean,
    onClearResumeHighlight: () -> Unit,
    onActiveElementVisible: (Boolean) -> Unit,
    isFollowingNarration: Boolean,
    onFollowingNarrationChanged: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val indexById = remember(sentences) { sentences.withIndex().associate { it.value.elementId to it.index } }
    val currentOnVisible by rememberUpdatedState(onVisibleElementChanged)
    var initialAnchorScrolled by remember(sentences) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                onClearResumeHighlight()
                onFollowingNarrationChanged(false)
            }
        }
    }

    val isActiveVisible by remember(activeElementId, indexById, listState) {
        derivedStateOf {
            val idx = activeElementId?.let(indexById::get)
            if (idx == null) true
            else {
                val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                idx in visible
            }
        }
    }
    LaunchedEffect(isActiveVisible) {
        onActiveElementVisible(isActiveVisible)
    }

    LaunchedEffect(listState, activeElementId, indexById) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress) {
                    val idx = activeElementId?.let(indexById::get)
                    val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
                    // Not when idx is null: narration is between sentences, and following then
                    // would drag the reader back as soon as the next sentence starts.
                    if (idx != null && idx in visible && !isFollowingNarration) {
                        onFollowingNarrationChanged(true)
                    }
                }
            }
    }

    LaunchedEffect(listState, sentences) {
        snapshotFlow { sentences.getOrNull(listState.firstVisibleItemIndex)?.elementId }
            .distinctUntilChanged()
            .collect { currentOnVisible(it) }
    }
    LaunchedEffect(initialAnchorElementId, indexById) {
        if (!initialAnchorScrolled && initialAnchorElementId != null) {
            val index = indexById[initialAnchorElementId]
            if (index != null) {
                listState.scrollToItem(index)
                initialAnchorScrolled = true
            }
        }
    }
    LaunchedEffect(activeElementId, indexById, isFollowingNarration) {
        val index = activeElementId?.let(indexById::get) ?: return@LaunchedEffect
        if (isFollowingNarration) {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (index !in visible.dropLast(1)) listState.animateScrollToItem(index)
        }
    }
    LaunchedEffect(scrollRequests, indexById) {
        scrollRequests.collect { target ->
            onFollowingNarrationChanged(!target.pauseAutoFollow)
            indexById[target.elementId]?.let { listState.animateScrollToItem(it) }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp
        val maxTextWidthPx = with(density) { 640.dp.toPx() }
        val effectiveMarginPx = if (isWideScreen && constraints.maxWidth > maxTextWidthPx) {
            ((constraints.maxWidth - maxTextWidthPx) / 2f).coerceAtLeast(horizontalMarginPx)
        } else {
            horizontalMarginPx
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = with(density) {
                PaddingValues(
                    start = effectiveMarginPx.toDp() - BlockHorizontalPadding,
                    end = effectiveMarginPx.toDp() - BlockHorizontalPadding,
                    top = PageTopPadding,
                    bottom = PageBottomPadding,
                )
            },
            verticalArrangement = Arrangement.spacedBy(with(density) { paragraphSpacingPx.toDp() }),
        ) {
            itemsIndexed(sentences, key = { _, s -> s.elementId }) { index, sentence ->
                if (sentence.text.isBlank()) return@itemsIndexed
                if (sentence.isChapterStart && index > 0) {
                    Spacer(Modifier.height(28.dp))
                }
                val selection = decorations.selection?.takeIf { it.sentence.elementId == sentence.elementId }?.range
                ReaderTextBlock(
                    sentence = sentence,
                    text = ReaderTextLogic.annotate(
                        sentence.text,
                        decorations.marks[sentence.elementId].orEmpty(),
                        selection,
                        decorations.selectionColor,
                    ),
                    textOffset = 0,
                    style = style,
                    isActive = sentence.elementId == activeElementId,
                    activeColor = activeColor,
                    selection = selection,
                    gestures = gestures,
                    isBookmarked = sentence.elementId in decorations.bookmarked,
                    isResumed = isSentenceResumed(sentence.elementId, resumedElementId),
                    isEInk = eInkOptimized || DeviceProfile.isCurrentDeviceEInk,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedCanvas(
    sentences: List<ReaderSentenceItem>,
    style: TextStyle,
    paragraphSpacingPx: Float,
    horizontalMarginPx: Float,
    activeElementId: String?,
    initialAnchorElementId: String?,
    activeColor: Color,
    decorations: ParagraphDecorations,
    gestures: ReaderTextGestures,
    scrollRequests: SharedFlow<ReaderScrollTarget>,
    onVisibleElementChanged: (String?) -> Unit,
    onPageChanged: (page: Int, pageCount: Int) -> Unit,
    pageTurnAnimation: PageTurnAnimation,
    eInkOptimized: Boolean,
    twoPageViewMode: TwoPageViewMode,
    backgroundColor: Color,
    modifier: Modifier,
    resumedElementId: String?,
    onClearResumeHighlight: () -> Unit,
    onActiveElementVisible: (Boolean) -> Unit,
    onSpreadGutterChanged: (Dp?) -> Unit,
    isFollowingNarration: Boolean,
    onFollowingNarrationChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val effectiveAnimation = remember(pageTurnAnimation, context, eInkOptimized) {
        effectivePageTurnAnimation(context, pageTurnAnimation, eInkOptimized)
    }
    val foldable = rememberFoldableLayoutInfo()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val topPaddingPx = with(density) { PageTopPadding.toPx() }
    val bottomPaddingPx = with(density) { PageBottomPadding.toPx() }
    val blockPaddingPx = with(density) { BlockHorizontalPadding.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val pageWidthPx = constraints.maxWidth
        val pageHeightPx = constraints.maxHeight - topPaddingPx - bottomPaddingPx
        val isDualPage = shouldDisplayDualPage(
            mode = twoPageViewMode,
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            isBookPosture = foldable.isBookPosture,
        )
        val hingePx = if (foldable.isBookPosture && foldable.foldingFeature != null) {
            foldable.foldingFeature.bounds.width().toFloat()
        } else 0f
        val gutterDp = if (hingePx > 0f) with(density) { hingePx.toDp().coerceAtLeast(24.dp) } else 32.dp
        DisposableEffect(isDualPage, gutterDp) {
            onSpreadGutterChanged(if (isDualPage) gutterDp else null)
            onDispose { onSpreadGutterChanged(null) }
        }
        val gutterPx = if (isDualPage) with(density) { gutterDp.toPx() } else 0f
        val effectiveMarginPx = if (pageWidthPx > with(density) { 640.dp.toPx() } && !isDualPage) {
            ((pageWidthPx - with(density) { 640.dp.toPx() }) / 2f).coerceAtLeast(horizontalMarginPx)
        } else {
            horizontalMarginPx
        }
        val textWidthPx = if (isDualPage) {
            val columnWidthPx = (pageWidthPx - 2 * (effectiveMarginPx - blockPaddingPx) - gutterPx) / 2f
            (columnWidthPx - 2 * blockPaddingPx).toInt().coerceAtLeast(1)
        } else {
            (pageWidthPx - 2 * effectiveMarginPx).toInt().coerceAtLeast(1)
        }

        // Paginating a whole book can take a while; measure off the main thread.
        val pages by produceState<List<ReaderPage>?>(null, sentences, style, textWidthPx, pageHeightPx, paragraphSpacingPx) {
            value = withContext(Dispatchers.Default) {
                paginate(measurer, sentences, style, textWidthPx, pageHeightPx, paragraphSpacingPx)
            }
        }
        val current = pages
        if (current == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        if (current.isEmpty()) return@BoxWithConstraints

        val spreadCount = if (isDualPage) ReaderPaginator.spreadCount(current.size) else current.size
        val pagerState = rememberPagerState { spreadCount }
        var curlSpreadIndex by remember { mutableIntStateOf(0) }

        // Central spread reader
        val currentSpread = if (effectiveAnimation == PageTurnAnimation.CURL) {
            curlSpreadIndex
        } else {
            pagerState.currentPage
        }

        // The paragraph at the top of the current page: kept across re-pagination (font/size/width changes).
        var anchorElementId by remember { mutableStateOf<String?>(null) }
        val currentOnVisible by rememberUpdatedState(onVisibleElementChanged)
        val currentOnPage by rememberUpdatedState(onPageChanged)

        val scope = rememberCoroutineScope()

        // Helper to navigate to a target spread.
        // In CURL mode, sets curlSpreadIndex directly without touching the unattached PagerState.
        // In HorizontalPager mode, schedules scrollToPage / animateScrollToPage on the composed pager.
        val goToSpread: (Int, Boolean) -> Unit = remember(pagerState, spreadCount, effectiveAnimation, scope) {
            { target, animate ->
                val clamped = target.coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
                if (effectiveAnimation == PageTurnAnimation.CURL) {
                    curlSpreadIndex = clamped
                } else {
                    scope.launch {
                        if (animate && effectiveAnimation != PageTurnAnimation.NONE) {
                            pagerState.animateScrollToPage(clamped)
                        } else {
                            pagerState.scrollToPage(clamped)
                        }
                    }
                }
            }
        }

        // When switching animation modes at runtime, carry the spread over between curlSpreadIndex and pagerState.
        var lastAnimation by remember { mutableStateOf(effectiveAnimation) }
        LaunchedEffect(effectiveAnimation) {
            if (lastAnimation != effectiveAnimation) {
                val clamped = (if (lastAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage)
                    .coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
                if (effectiveAnimation == PageTurnAnimation.CURL) {
                    curlSpreadIndex = clamped
                } else {
                    pagerState.scrollToPage(clamped)
                }
                lastAnimation = effectiveAnimation
            }
        }

        val isActiveVisible by remember(activeElementId, current, isDualPage, effectiveAnimation) {
            derivedStateOf {
                if (activeElementId == null) true
                else {
                    val targetPage = ReaderPaginator.pageOf(current, activeElementId)
                    if (targetPage == null) true
                    else {
                        val targetSpread = if (isDualPage) targetPage / 2 else targetPage
                        val spread = if (effectiveAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage
                        targetSpread == spread
                    }
                }
            }
        }
        LaunchedEffect(isActiveVisible) {
            onActiveElementVisible(isActiveVisible)
        }

        // The curl flipper draws turns itself, so the pager does not report them as scrolling.
        var isCurling by remember(effectiveAnimation) { mutableStateOf(false) }
        val currentActiveElementId by rememberUpdatedState(activeElementId)
        val currentIsFollowing by rememberUpdatedState(isFollowingNarration)
        LaunchedEffect(current, isDualPage, effectiveAnimation) {
            // Resume following only when the spread on screen and narration's spread come together
            // (narration reaches this spread, or the reader turns to narration's). A curl does not
            // move the spread until it lands, so a sentence change mid-turn must not count as arriving.
            var wasOnNarrationSpread = true
            snapshotFlow {
                val spread = if (effectiveAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage
                val inProgress = if (effectiveAnimation == PageTurnAnimation.CURL) isCurling else (pagerState.isScrollInProgress || isCurling)
                val targetPage = ReaderPaginator.pageOf(current, currentActiveElementId)
                Triple(spread, inProgress, targetPage?.let { if (isDualPage) it / 2 else it })
            }
                .distinctUntilChanged()
                .collect { (page, inProgress, targetSpread) ->
                    // Not when targetSpread is null: narration is between sentences.
                    if (inProgress || targetSpread == null) return@collect
                    val onNarrationSpread = page == targetSpread
                    if (onNarrationSpread && !wasOnNarrationSpread && !currentIsFollowing) {
                        onFollowingNarrationChanged(true)
                    }
                    wasOnNarrationSpread = onNarrationSpread
                }
        }

        // Initial restore: navigates to the saved reading position without blocking the spread observer.
        LaunchedEffect(current, isDualPage) {
            val targetPage = ReaderPaginator.pageOf(current, anchorElementId ?: initialAnchorElementId ?: activeElementId) ?: 0
            val targetSpread = if (isDualPage) targetPage / 2 else targetPage
            val clamped = targetSpread.coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
            if (effectiveAnimation == PageTurnAnimation.CURL) {
                curlSpreadIndex = clamped
            } else {
                pagerState.scrollToPage(clamped)
            }
        }

        // Spread observer: updates visible element and page counts as the spread changes.
        LaunchedEffect(current, isDualPage, effectiveAnimation) {
            snapshotFlow {
                if (effectiveAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage
            }
                .distinctUntilChanged()
                .collect { spreadIndex ->
                    val visiblePage = if (isDualPage) spreadIndex * 2 else spreadIndex
                    // The first paragraph that starts on screen: one continued from the previous page
                    // belongs to that page, so it would send restores and narration back a page.
                    val onScreen = (visiblePage until visiblePage + if (isDualPage) 2 else 1)
                        .flatMap { current.getOrNull(it)?.fragments.orEmpty() }
                    anchorElementId = (onScreen.firstOrNull { it.start == 0 } ?: onScreen.firstOrNull())?.sentence?.elementId
                    currentOnVisible(anchorElementId)
                    currentOnPage(visiblePage, current.size)
                }
        }

        LaunchedEffect(pagerState) {
            var dragStartPage: Int? = null
            pagerState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        dragStartPage = pagerState.currentPage
                        onFollowingNarrationChanged(false)
                    }
                    is DragInteraction.Stop -> {
                        if (dragStartPage != null && pagerState.currentPage != dragStartPage) {
                            try {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } catch (_: Exception) {}
                            onClearResumeHighlight()
                        }
                        dragStartPage = null
                    }
                    is DragInteraction.Cancel -> {
                        dragStartPage = null
                    }
                }
            }
        }

        LaunchedEffect(activeElementId, current, effectiveAnimation, isDualPage, isFollowingNarration) {
            if (!isFollowingNarration) return@LaunchedEffect
            val targetPage = ReaderPaginator.pageOf(current, activeElementId) ?: return@LaunchedEffect
            val targetSpread = if (isDualPage) targetPage / 2 else targetPage
            val spread = if (effectiveAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage
            if (targetSpread != spread) {
                if (effectiveAnimation == PageTurnAnimation.CURL) {
                    if (isCurling) {
                        snapshotFlow { isCurling }.first { !it }
                    }
                } else {
                    if (pagerState.isScrollInProgress) {
                        snapshotFlow { pagerState.isScrollInProgress }
                            .first { !it }
                    }
                }
                if (!isFollowingNarration) return@LaunchedEffect
                val currentSpreadNow = if (effectiveAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage
                if (targetSpread != currentSpreadNow) {
                    goToSpread(targetSpread, effectiveAnimation == PageTurnAnimation.SLIDE || effectiveAnimation == PageTurnAnimation.COVER)
                }
            }
        }

        LaunchedEffect(scrollRequests, current, effectiveAnimation, isDualPage, spreadCount) {
            scrollRequests.collect { target ->
                onFollowingNarrationChanged(!target.pauseAutoFollow)
                ReaderPaginator.pageOf(current, target.elementId)?.let { targetPage ->
                    val targetSpread = if (isDualPage) targetPage / 2 else targetPage
                    goToSpread(targetSpread, effectiveAnimation == PageTurnAnimation.SLIDE || effectiveAnimation == PageTurnAnimation.COVER)
                }
            }
        }

        LaunchedEffect(pagerState, spreadCount, effectiveAnimation) {
            if (effectiveAnimation == PageTurnAnimation.CURL) return@LaunchedEffect
            HardwareKeyDispatcher.pageTurnEvents.collect { delta ->
                val target = (pagerState.currentPage + delta).coerceIn(0, spreadCount - 1)
                if (target != pagerState.currentPage) {
                    onFollowingNarrationChanged(false)
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onClearResumeHighlight()
                    goToSpread(target, effectiveAnimation != PageTurnAnimation.NONE)
                }
            }
        }

        val currentDecorations by rememberUpdatedState(decorations)
        // Left/right fifths of the screen turn pages; the middle keeps the normal tap behavior.
        val pageGestures = remember(gestures, currentSpread, pageWidthPx, effectiveAnimation, spreadCount) {
            object : ReaderTextGestures by gestures {
                override fun onTap(positionInWindow: Offset) {
                    val zone = pageTurnZone(positionInWindow.x, pageWidthPx.toFloat())
                    if (currentDecorations.selection != null || zone == 0) {
                        gestures.onTap(positionInWindow)
                        return
                    }
                    val spread = if (effectiveAnimation == PageTurnAnimation.CURL) curlSpreadIndex else pagerState.currentPage
                    val target = (spread + zone).coerceIn(0, spreadCount - 1)
                    if (target != spread) {
                        onFollowingNarrationChanged(false)
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } catch (_: Exception) {}
                        onClearResumeHighlight()
                        goToSpread(target, effectiveAnimation != PageTurnAnimation.NONE)
                    }
                }
            }
        }

        if (effectiveAnimation == PageTurnAnimation.CURL) {
            PageCurlFlipper(
                current = current,
                spreadCount = spreadCount,
                currentSpreadIndex = curlSpreadIndex,
                onTurnInitiated = { onFollowingNarrationChanged(false) },
                onCurlingChanged = { isCurling = it },
                onSpreadChanged = { target ->
                    onFollowingNarrationChanged(false)
                    onClearResumeHighlight()
                    goToSpread(target, false)
                },
                isDualPage = isDualPage,
                decorations = currentDecorations,
                style = style,
                activeElementId = activeElementId,
                activeColor = activeColor,
                gestures = gestures,
                paragraphSpacingPx = paragraphSpacingPx,
                effectiveMarginPx = effectiveMarginPx,
                blockPaddingPx = blockPaddingPx,
                gutterDp = gutterDp,
                backgroundColor = backgroundColor,
                foldable = foldable,
                isEInk = eInkOptimized || DeviceProfile.isCurrentDeviceEInk,
                onClearResumeHighlight = onClearResumeHighlight,
                haptic = haptic,
                hardwareKeyEvents = HardwareKeyDispatcher.pageTurnEvents,
                resumedElementId = resumedElementId,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val hasActiveSelection = currentDecorations.selection != null
            val currentHasSelection by rememberUpdatedState(hasActiveSelection)
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = effectiveAnimation != PageTurnAnimation.NONE,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .then(
                        if (effectiveAnimation == PageTurnAnimation.NONE && shouldAllowHorizontalPageTurnDrag(hasActiveSelection)) {
                            Modifier.pointerInput(spreadCount, hasActiveSelection) {
                                var totalDrag = 0f
                                val swipeThreshold = 36.dp.toPx()
                                var handled = false
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalDrag = 0f
                                        handled = false
                                    },
                                    onDragEnd = {
                                        if (currentHasSelection) return@detectHorizontalDragGestures
                                        if (!handled && kotlin.math.abs(totalDrag) > swipeThreshold) {
                                            val delta = if (totalDrag < 0f) 1 else -1
                                            val target = (pagerState.currentPage + delta).coerceIn(0, spreadCount - 1)
                                            if (target != pagerState.currentPage) {
                                                onFollowingNarrationChanged(false)
                                                try {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                } catch (_: Exception) {}
                                                onClearResumeHighlight()
                                                goToSpread(target, false)
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        totalDrag = 0f
                                        handled = false
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        if (currentHasSelection) return@detectHorizontalDragGestures
                                        change.consume()
                                        totalDrag += dragAmount
                                        if (!handled && kotlin.math.abs(totalDrag) > swipeThreshold * 2) {
                                            handled = true
                                            val delta = if (totalDrag < 0f) 1 else -1
                                            val target = (pagerState.currentPage + delta).coerceIn(0, spreadCount - 1)
                                            if (target != pagerState.currentPage) {
                                                onFollowingNarrationChanged(false)
                                                try {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                } catch (_: Exception) {}
                                                onClearResumeHighlight()
                                                goToSpread(target, false)
                                            }
                                        }
                                    }
                                )
                            }
                        } else Modifier
                    ),
                beyondViewportPageCount = 1,
            ) { spreadIndex ->
                var pageOrigin by remember { mutableStateOf(Offset.Zero) }
                val pageOffset = pagerState.getOffsetDistanceInPages(spreadIndex).coerceIn(-1f, 1f)
                val spreadZIndex = if (pageOffset <= 0f) 2f else 1f
                val isEInk = eInkOptimized || DeviceProfile.isCurrentDeviceEInk
                if (isDualPage) {
                    val leftPageIndex = spreadIndex * 2
                    val rightPageIndex = spreadIndex * 2 + 1
                    val leftPage = current[leftPageIndex]
                    val rightPage = current.getOrNull(rightPageIndex)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(backgroundColor)
                            .zIndex(spreadZIndex)
                    ) {
                        SpreadCreaseSpine(
                            foldable = foldable,
                            gutterDp = gutterDp,
                            isDark = backgroundColor.luminance() < 0.5f,
                            isEInk = isEInk,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { pageOrigin = it.positionInWindow() }
                                .pointerInput(pageGestures) { detectTapGestures { pageGestures.onTap(it + pageOrigin) } }
                                .padding(
                                    horizontal = with(density) { (effectiveMarginPx - blockPaddingPx).toDp() },
                                )
                                .padding(top = PageTopPadding, bottom = PageBottomPadding),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        if (effectiveAnimation == PageTurnAnimation.COVER) {
                                            applyLeftPageSpreadAnimation(effectiveAnimation, pageOffset, density, pageWidthPx.toFloat())
                                        }
                                    }
                            ) {
                                PageContent(
                                    page = leftPage,
                                    decorations = currentDecorations,
                                    style = style,
                                    activeElementId = activeElementId,
                                    activeColor = activeColor,
                                    pageGestures = pageGestures,
                                    paragraphSpacingPx = paragraphSpacingPx,
                                    density = density,
                                    resumedElementId = resumedElementId,
                                    isEInk = isEInk,
                                )
                            }
                            Spacer(
                                Modifier
                                    .width(gutterDp)
                                    .fillMaxHeight()
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        if (effectiveAnimation == PageTurnAnimation.COVER) {
                                            applyRightPageSpreadAnimation(effectiveAnimation, pageOffset, density, pageWidthPx.toFloat())
                                        }
                                    }
                            ) {
                                if (rightPage != null) {
                                    PageContent(
                                        page = rightPage,
                                        decorations = currentDecorations,
                                        style = style,
                                        activeElementId = activeElementId,
                                        activeColor = activeColor,
                                        pageGestures = pageGestures,
                                        paragraphSpacingPx = paragraphSpacingPx,
                                        density = density,
                                        resumedElementId = resumedElementId,
                                        isEInk = isEInk,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(backgroundColor)
                            .zIndex(spreadZIndex)
                            .graphicsLayer {
                                applyPageAnimation(
                                    effectiveAnimation = effectiveAnimation,
                                    pageOffset = pageOffset,
                                    density = density,
                                    width = size.width,
                                )
                            }
                            .onGloballyPositioned { pageOrigin = it.positionInWindow() }
                            .pointerInput(pageGestures) {
                                detectTapGestures { offset ->
                                    pageGestures.onTap(offset + pageOrigin)
                                }
                            }
                            .padding(
                                horizontal = with(density) { (effectiveMarginPx - blockPaddingPx).toDp() },
                            )
                            .padding(top = PageTopPadding, bottom = PageBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(with(density) { paragraphSpacingPx.toDp() }),
                    ) {
                        PageContent(
                            page = current[spreadIndex],
                            decorations = currentDecorations,
                            style = style,
                            activeElementId = activeElementId,
                            activeColor = activeColor,
                            pageGestures = pageGestures,
                            paragraphSpacingPx = paragraphSpacingPx,
                            density = density,
                            resumedElementId = resumedElementId,
                            isEInk = eInkOptimized || DeviceProfile.isCurrentDeviceEInk,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PageContent(
    page: ReaderPage,
    decorations: ParagraphDecorations,
    style: TextStyle,
    activeElementId: String?,
    activeColor: Color,
    pageGestures: ReaderTextGestures,
    paragraphSpacingPx: Float,
    density: androidx.compose.ui.unit.Density,
    resumedElementId: String? = null,
    isEInk: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(with(density) { paragraphSpacingPx.toDp() }),
    ) {
        page.fragments.forEach { fragment ->
            val id = fragment.sentence.elementId
            val selection = decorations.selection?.takeIf { it.sentence.elementId == id }?.range
            ReaderTextBlock(
                sentence = fragment.sentence,
                text = ReaderTextLogic.annotate(
                    fragment.text,
                    decorations.marks[id].orEmpty(),
                    selection,
                    decorations.selectionColor,
                    offset = fragment.start,
                ),
                textOffset = fragment.start,
                style = style,
                isActive = id == activeElementId,
                activeColor = activeColor,
                selection = selection,
                gestures = pageGestures,
                isBookmarked = fragment.isParagraphStart && id in decorations.bookmarked,
                verticalPadding = 0.dp,
                isResumed = isSentenceResumed(id, resumedElementId),
                isEInk = isEInk,
            )
        }
    }
}

internal fun shouldAllowHorizontalPageTurnDrag(hasActiveSelection: Boolean): Boolean = !hasActiveSelection

private fun GraphicsLayerScope.applyPageAnimation(
    effectiveAnimation: PageTurnAnimation,
    pageOffset: Float,
    density: androidx.compose.ui.unit.Density,
    width: Float,
) {
    if (kotlin.math.abs(pageOffset) >= 0.999f) {
        alpha = 0f
        rotationY = 0f
        translationX = 0f
        scaleX = 1f
        scaleY = 1f
        shadowElevation = 0f
        renderEffect = null
        return
    }
    when (effectiveAnimation) {
        PageTurnAnimation.NONE -> {
            alpha = 1f
            rotationY = 0f
            translationX = 0f
            scaleX = 1f
            scaleY = 1f
            shadowElevation = 0f
            renderEffect = null
        }
        PageTurnAnimation.SLIDE -> {
            rotationY = 0f
            translationX = 0f
            scaleX = 1f
            scaleY = 1f
            shadowElevation = 0f
            renderEffect = null
            alpha = (1f - kotlin.math.abs(pageOffset) * 0.15f).coerceIn(0f, 1f)
        }
        PageTurnAnimation.COVER, PageTurnAnimation.CURL -> {
            rotationY = 0f
            scaleX = 1f
            scaleY = 1f
            renderEffect = null
            if (pageOffset < 0f) {
                // Current page sliding off to the left over stationary page beneath
                translationX = 0f
                shadowElevation = (kotlin.math.abs(pageOffset) * 14f) * density.density
                alpha = 1f
            } else if (pageOffset > 0f) {
                // Incoming page stays stationary underneath
                translationX = -pageOffset * width
                shadowElevation = 0f
                alpha = 1f
            } else {
                translationX = 0f
                shadowElevation = 0f
                alpha = 1f
            }
        }
    }
}

private fun GraphicsLayerScope.applyLeftPageSpreadAnimation(
    effectiveAnimation: PageTurnAnimation,
    pageOffset: Float,
    density: androidx.compose.ui.unit.Density,
    spreadWidth: Float,
) {
    if (kotlin.math.abs(pageOffset) >= 0.999f) {
        alpha = 0f
        translationX = 0f
        rotationY = 0f
        shadowElevation = 0f
        return
    }
    when (effectiveAnimation) {
        PageTurnAnimation.NONE, PageTurnAnimation.SLIDE -> {
            translationX = 0f
            rotationY = 0f
            alpha = 1f
            shadowElevation = 0f
        }
        PageTurnAnimation.COVER, PageTurnAnimation.CURL -> {
            if (pageOffset < 0f) {
                // Opposite page stays COMPLETELY STATIONARY!
                translationX = -pageOffset * spreadWidth
                rotationY = 0f
                alpha = 1f
                shadowElevation = 0f
            } else if (pageOffset > 0f) {
                // Underneath next spread pinned stationary
                translationX = -pageOffset * spreadWidth
                rotationY = 0f
                alpha = 1f
                shadowElevation = 0f
            } else {
                translationX = 0f
                rotationY = 0f
                alpha = 1f
                shadowElevation = 0f
            }
        }
    }
}

private fun GraphicsLayerScope.applyRightPageSpreadAnimation(
    effectiveAnimation: PageTurnAnimation,
    pageOffset: Float,
    density: androidx.compose.ui.unit.Density,
    spreadWidth: Float,
) {
    if (kotlin.math.abs(pageOffset) >= 0.999f) {
        alpha = 0f
        translationX = 0f
        rotationY = 0f
        shadowElevation = 0f
        return
    }
    when (effectiveAnimation) {
        PageTurnAnimation.NONE, PageTurnAnimation.SLIDE -> {
            translationX = 0f
            rotationY = 0f
            alpha = 1f
            shadowElevation = 0f
        }
        PageTurnAnimation.COVER, PageTurnAnimation.CURL -> {
            if (pageOffset < 0f) {
                // Right leaf slides off to the left over left page
                val progress = -pageOffset
                translationX = -progress * (spreadWidth * 0.5f)
                rotationY = 0f
                shadowElevation = (progress * 14f) * density.density
                alpha = (1f - progress * 0.2f).coerceIn(0f, 1f)
            } else if (pageOffset > 0f) {
                // Next spread's right page stationary underneath
                translationX = -pageOffset * spreadWidth
                rotationY = 0f
                alpha = 1f
                shadowElevation = 0f
            } else {
                translationX = 0f
                rotationY = 0f
                alpha = 1f
                shadowElevation = 0f
            }
        }
    }
}

/** Resolves the effective animation, honoring e-ink optimization and the system's "Remove animations" accessibility setting. */
fun effectivePageTurnAnimation(
    context: Context,
    preference: PageTurnAnimation,
    eInkOptimized: Boolean = false,
): PageTurnAnimation {
    if (eInkOptimized) {
        return PageTurnAnimation.NONE
    }
    val durationScale = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    } catch (_: Exception) {
        1f
    }
    return if (durationScale == 0f) PageTurnAnimation.NONE else preference
}

/** Determines whether the reader canvas renders a dual-page spread. */
fun shouldDisplayDualPage(
    mode: TwoPageViewMode,
    widthDp: Float,
    heightDp: Float,
    isBookPosture: Boolean,
): Boolean = when (mode) {
    TwoPageViewMode.OFF -> false
    TwoPageViewMode.ON -> true
    TwoPageViewMode.AUTO -> isBookPosture || (widthDp >= 680f && heightDp >= 480f && widthDp > heightDp)
}

/** -1 for the left fifth (previous page), +1 for the right fifth (next page), 0 for the middle. */
internal fun pageTurnZone(x: Float, width: Float): Int = when {
    width <= 0f -> 0
    x < width * 0.2f -> -1
    x > width * 0.8f -> 1
    else -> 0
}

private fun paginate(
    measurer: TextMeasurer,
    sentences: List<ReaderSentenceItem>,
    style: TextStyle,
    textWidthPx: Int,
    pageHeightPx: Float,
    paragraphSpacingPx: Float,
): List<ReaderPage> {
    if (pageHeightPx <= 0f) return emptyList()
    return ReaderPaginator.paginate(sentences, pageHeightPx, paragraphSpacingPx) { sentence ->
        val isHeading = sentence.isChapterStart && (
            (sentence.chapterTitle != null && sentence.text.trim().equals(sentence.chapterTitle.trim(), ignoreCase = true)) ||
            isChapterHeadingText(sentence.text)
        )
        val lineStyle = if (isHeading) {
            style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * 1.15f)
        } else {
            style
        }
        val layout = measurer.measure(
            text = AnnotatedString(sentence.text),
            style = lineStyle,
            constraints = Constraints(maxWidth = textWidthPx),
        )
        (0 until layout.lineCount).map { line ->
            MeasuredLine(
                start = layout.getLineStart(line),
                end = layout.getLineEnd(line),
                height = layout.getLineBottom(line) - layout.getLineTop(line),
            )
        }
    }
}

@Composable
internal fun SpreadCreaseSpine(
    foldable: FoldableLayoutInfo,
    gutterDp: Dp,
    isDark: Boolean,
    isEInk: Boolean,
    modifier: Modifier = Modifier,
    /** Fade the spine out at the top and bottom; off where it continues past this box. */
    fadeEnds: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val totalWidthPx = constraints.maxWidth.toFloat()
        val totalHeightPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        val hingeBounds = foldable.foldingFeature?.bounds
        val centerX = if (foldable.isBookPosture && hingeBounds != null && hingeBounds.width() > 0) {
            (hingeBounds.left + hingeBounds.width() / 2f).coerceIn(totalWidthPx * 0.2f, totalWidthPx * 0.8f)
        } else {
            totalWidthPx / 2f
        }

        val gutterPx = with(density) { gutterDp.toPx() }
        val halfGutter = gutterPx / 2f
        val left = centerX - halfGutter
        val right = centerX + halfGutter

        if (isEInk) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.25f),
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, totalHeightPx),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val shadowAlpha = if (isDark) 0.36f else 0.14f
                val spineColor = Color.Black

                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            spineColor.copy(alpha = shadowAlpha * 0.25f),
                            spineColor.copy(alpha = shadowAlpha),
                            spineColor.copy(alpha = shadowAlpha * 0.25f),
                            Color.Transparent,
                        ),
                        startX = left,
                        endX = right,
                    ),
                    topLeft = Offset(left, 0f),
                    size = androidx.compose.ui.geometry.Size(gutterPx, totalHeightPx),
                )

                val fadeHeight = if (fadeEnds) (totalHeightPx * 0.08f).coerceAtMost(48.dp.toPx()) else 0f
                if (fadeEnds) drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            spineColor.copy(alpha = shadowAlpha * 0.1f),
                        ),
                        startY = 0f,
                        endY = fadeHeight,
                    ),
                    topLeft = Offset(left, 0f),
                    size = androidx.compose.ui.geometry.Size(gutterPx, fadeHeight),
                )
                if (fadeEnds) drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            spineColor.copy(alpha = shadowAlpha * 0.1f),
                            Color.Transparent,
                        ),
                        startY = totalHeightPx - fadeHeight,
                        endY = totalHeightPx,
                    ),
                    topLeft = Offset(left, totalHeightPx - fadeHeight),
                    size = androidx.compose.ui.geometry.Size(gutterPx, fadeHeight),
                )

                val centerLineAlpha = if (isDark) 0.22f else 0.08f
                drawLine(
                    color = spineColor.copy(alpha = centerLineAlpha),
                    start = Offset(centerX, fadeHeight * 0.5f),
                    end = Offset(centerX, totalHeightPx - fadeHeight * 0.5f),
                    strokeWidth = 1f,
                )
            }
        }
    }
}


