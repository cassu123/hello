package org.hello.reader.feature.reader

import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Controller/state for [PageCurlFlipper], coordinating touch drag, synthetic turns, and page capture.
 */
@Stable
class PageCurlState(
    val spreadCount: Int,
    val isDualPage: Boolean,
) {
    var isCurling by mutableStateOf(false)
        internal set

    var isForward by mutableStateOf(true)
        internal set

    var activeCorner by mutableStateOf(CurlCorner.BOTTOM_RIGHT)
        internal set

    // Current touch position in turning leaf local coordinates, updated synchronously during drag
    var touchPos by mutableStateOf(Offset.Zero)
        internal set

    // Pending target spread to prevent single-frame flash when turn finishes
    var pendingTargetSpread by mutableStateOf<Int?>(null)
        internal set

    // Animatable for smooth release flings and synthetic turns
    val animatingOffset = Animatable(Offset.Zero, Offset.VectorConverter)

    // Reusable paths & matrix to minimize allocations during draw
    internal val frontPath = Path()
    internal val flapPath = Path()
    internal val reflectedFlapPath = Path()
    internal val shadowPath = Path()
    internal val androidMatrix = Matrix()

    /**
     * Handles the start of a drag gesture synchronously based on the drag delta direction.
     * Returns true if a curl turn was initiated.
     */
    fun onDragStarted(
        downPos: Offset,
        dragDelta: Offset,
        currentSpreadIndex: Int,
        totalHeight: Float,
        leafWidth: Float,
    ): Boolean {
        if (abs(dragDelta.x) < 0.5f) return false
        val fwd = dragDelta.x < 0f // Dragging to the left -> forward; dragging to the right -> backward
        if (fwd && currentSpreadIndex >= spreadCount - 1) return false
        if (!fwd && currentSpreadIndex <= 0) return false

        isForward = fwd
        activeCorner = when {
            fwd && downPos.y >= totalHeight * 0.5f -> CurlCorner.BOTTOM_RIGHT
            fwd -> CurlCorner.TOP_RIGHT
            downPos.y >= totalHeight * 0.5f -> CurlCorner.BOTTOM_LEFT
            else -> CurlCorner.TOP_LEFT
        }

        val initialX = if (fwd) leafWidth else 0f
        val initialY = if (activeCorner.isBottomSide) totalHeight else 0f
        touchPos = Offset(initialX + dragDelta.x, initialY + dragDelta.y)
        pendingTargetSpread = if (fwd) currentSpreadIndex + 1 else currentSpreadIndex - 1
        isCurling = true
        return true
    }

    /** Updates touch position synchronously during drag frames. */
    fun onDragDelta(dragDelta: Offset, leafWidth: Float = 0f, totalHeight: Float = 0f) {
        val next = touchPos + dragDelta
        touchPos = if (leafWidth > 0f) {
            val minX = if (isForward) -leafWidth * 1.3f else -leafWidth * 0.1f
            val maxX = if (isForward) leafWidth * 1.1f else leafWidth * 2.3f
            val maxY = if (totalHeight > 0f) totalHeight + 100f else next.y
            Offset(next.x.coerceIn(minX, maxX), next.y.coerceIn(-100f, maxY))
        } else {
            next
        }
    }

    /**
     * Determines whether the current drag progress and velocity completes the turn.
     */
    fun shouldCompleteTurn(progress: Float, velocityX: Float): Boolean = if (isForward) {
        (progress >= 0.40f && velocityX <= 0f) || velocityX < -600f
    } else {
        (progress >= 0.40f && velocityX >= 0f) || velocityX > 600f
    }
}

@Composable
fun rememberPageCurlState(
    spreadCount: Int,
    isDualPage: Boolean,
): PageCurlState = remember(spreadCount, isDualPage) {
    PageCurlState(spreadCount, isDualPage)
}

@Composable
private fun rememberGraphicsLayer(): GraphicsLayer {
    val context = LocalGraphicsContext.current
    val layer = remember { context.createGraphicsLayer() }
    DisposableEffect(layer) {
        onDispose {
            context.releaseGraphicsLayer(layer)
        }
    }
    return layer
}

/**
 * Dedicated 2D paper-curl flipper composable.
 *
 * Replaces HorizontalPager for [PageTurnAnimation.CURL]:
 * - At rest, renders the current spread interactively (taps, text selection, readaloud highlight).
 * - On turn start (gesture, tap-zone, or hardware key), captures pages into [GraphicsLayer]s.
 * - Draws flat-fold geometry with dynamic perpendicular bisector, spine clamping, and realistic shadows.
 * - Works identically across all Android versions (Android 12 onn tablet through Android 15+).
 */
@Composable
fun PageCurlFlipper(
    current: List<ReaderPage>,
    spreadCount: Int,
    currentSpreadIndex: Int,
    onSpreadChanged: (Int) -> Unit,
    isDualPage: Boolean,
    decorations: ParagraphDecorations,
    style: TextStyle,
    activeElementId: String?,
    activeColor: Color,
    gestures: ReaderTextGestures,
    paragraphSpacingPx: Float,
    effectiveMarginPx: Float,
    blockPaddingPx: Float,
    gutterDp: Dp,
    backgroundColor: Color,
    foldable: FoldableLayoutInfo,
    isEInk: Boolean,
    onClearResumeHighlight: () -> Unit,
    haptic: HapticFeedback,
    hardwareKeyEvents: SharedFlow<Int>,
    modifier: Modifier = Modifier,
    resumedElementId: String? = null,
    onTurnInitiated: () -> Unit = {},
    /** True from the start of a turn until the pager lands on its spread (or the turn is cancelled). */
    onCurlingChanged: (Boolean) -> Unit = {},
) {
    val state = rememberPageCurlState(spreadCount, isDualPage)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // GraphicsLayers to record pages without recomposing text during turns
    val currentSpreadLayer = rememberGraphicsLayer()
    val nextSpreadLayer = rememberGraphicsLayer()
    val prevSpreadLayer = rememberGraphicsLayer()

    val currentDecorations by rememberUpdatedState(decorations)
    val currentOnCurlingChanged by rememberUpdatedState(onCurlingChanged)
    LaunchedEffect(state) {
        snapshotFlow { state.isCurling }.collect { currentOnCurlingChanged(it) }
    }

    DisposableEffect(Unit) {
        onDispose {
            state.pendingTargetSpread = null
            state.isCurling = false
        }
    }

    // Clear curling state whenever the current spread index updates
    LaunchedEffect(currentSpreadIndex) {
        state.pendingTargetSpread = null
        state.isCurling = false
    }

    val noOpGestures = remember {
        object : ReaderTextGestures {
            override fun onTap(positionInWindow: Offset) {}
            override fun onDoubleTap(sentence: ReaderSentenceItem) {}
            override fun onSelectionStart(sentence: ReaderSentenceItem, offset: Int) {}
            override fun onSelectionExtend(sentence: ReaderSentenceItem, offset: Int) {}
            override fun onSelectionEnd(sentence: ReaderSentenceItem, boundsInWindow: Rect) {}
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        val totalWidth = constraints.maxWidth.toFloat()
        val totalHeight = constraints.maxHeight.toFloat()
        val leafWidth = if (isDualPage) totalWidth / 2f else totalWidth
        val leafSize = Size(leafWidth, totalHeight)

        // Hardware keys (volume buttons or D-pad)
        LaunchedEffect(hardwareKeyEvents, currentSpreadIndex, spreadCount, leafSize) {
            hardwareKeyEvents.collect { delta ->
                if (state.isCurling) return@collect
                val target = (currentSpreadIndex + delta).coerceIn(0, spreadCount - 1)
                if (target != currentSpreadIndex) {
                    onTurnInitiated()
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } catch (_: Exception) {}
                    onClearResumeHighlight()
                    val isFwd = delta > 0
                    state.isForward = isFwd
                    state.activeCorner = if (isFwd) CurlCorner.BOTTOM_RIGHT else CurlCorner.BOTTOM_LEFT
                    state.isCurling = true
                    state.pendingTargetSpread = target
                    scope.launch {
                        runSyntheticCurl(
                            state = state,
                            isForward = isFwd,
                            leafSize = leafSize,
                            onFinished = {
                                onSpreadChanged(target)
                            },
                        )
                    }
                }
            }
        }

        // Tap gestures on page zones (left/right fifths)
        var flipperOrigin by remember { mutableStateOf(Offset.Zero) }
        var flipperWidth by remember { mutableFloatStateOf(0f) }

        val pageGestures = remember(gestures, currentSpreadIndex, spreadCount, isDualPage, leafSize) {
            object : ReaderTextGestures by gestures {
                override fun onTap(positionInWindow: Offset) {
                    val zone = pageTurnZone(positionInWindow.x - flipperOrigin.x, flipperWidth)
                    if (currentDecorations.selection != null || zone == 0) {
                        gestures.onTap(positionInWindow)
                        return
                    }
                    if (state.isCurling) return
                    val target = (currentSpreadIndex + zone).coerceIn(0, spreadCount - 1)
                    if (target != currentSpreadIndex) {
                        onTurnInitiated()
                        try {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } catch (_: Exception) {}
                        onClearResumeHighlight()
                        val isFwd = zone > 0
                        state.isForward = isFwd
                        state.activeCorner = if (isFwd) CurlCorner.BOTTOM_RIGHT else CurlCorner.BOTTOM_LEFT
                        state.isCurling = true
                        state.pendingTargetSpread = target
                        scope.launch {
                            runSyntheticCurl(
                                state = state,
                                isForward = isFwd,
                                leafSize = leafSize,
                                onFinished = {
                                    onSpreadChanged(target)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Detect drag gestures on the container so child long-press text selection takes precedence
        val velocityTracker = remember { VelocityTracker() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentSpreadIndex, spreadCount, currentDecorations.selection != null, leafSize) {
                    if (currentDecorations.selection != null) return@pointerInput
                    var dragActive = false
                    var downPos = Offset.Zero

                    detectDragGestures(
                        onDragStart = { pos ->
                            velocityTracker.resetTracking()
                            downPos = pos
                            dragActive = false
                        },
                        onDrag = { change, dragAmount ->
                            if (!dragActive) {
                                if (state.onDragStarted(
                                        downPos = downPos,
                                        dragDelta = dragAmount,
                                        currentSpreadIndex = currentSpreadIndex,
                                        totalHeight = totalHeight,
                                        leafWidth = leafWidth,
                                    )
                                ) {
                                    dragActive = true
                                    onClearResumeHighlight()
                                    change.consume()
                                }
                            } else {
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                state.onDragDelta(dragAmount, leafWidth, totalHeight)
                            }
                        },
                        onDragEnd = {
                            if (!dragActive) return@detectDragGestures
                            dragActive = false
                            val velocity = velocityTracker.calculateVelocity()
                            val result = CurlGeometry.compute(
                                pageSize = leafSize,
                                corner = state.activeCorner,
                                touch = state.touchPos,
                            )
                            val completesTurn = state.shouldCompleteTurn(result.progress, velocity.x)
                            val targetX = if (state.isForward) {
                                if (completesTurn) -leafWidth else leafWidth
                            } else {
                                if (completesTurn) 2f * leafWidth else 0f
                            }
                            val targetY = if (state.activeCorner.isBottomSide) totalHeight else 0f
                            val targetSpread = state.pendingTargetSpread

                            scope.launch {
                                try {
                                    state.animatingOffset.snapTo(state.touchPos)
                                    state.animatingOffset.animateTo(
                                        targetValue = Offset(targetX, targetY),
                                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                                    ) {
                                        state.touchPos = value
                                    }
                                    if (completesTurn && targetSpread != null && targetSpread != currentSpreadIndex) {
                                        onTurnInitiated()
                                        state.pendingTargetSpread = null
                                        state.isCurling = false
                                        onSpreadChanged(targetSpread)
                                    } else {
                                        state.pendingTargetSpread = null
                                        state.isCurling = false
                                    }
                                } catch (_: CancellationException) {
                                    state.pendingTargetSpread = null
                                    state.isCurling = false
                                } finally {
                                    if (!completesTurn || targetSpread == null || targetSpread == currentSpreadIndex) {
                                        state.pendingTargetSpread = null
                                        state.isCurling = false
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            if (!dragActive) return@detectDragGestures
                            dragActive = false
                            val targetX = if (state.isForward) leafWidth else 0f
                            val targetY = if (state.activeCorner.isBottomSide) totalHeight else 0f
                            scope.launch {
                                try {
                                    state.animatingOffset.snapTo(state.touchPos)
                                    state.animatingOffset.animateTo(
                                        targetValue = Offset(targetX, targetY),
                                        animationSpec = tween(durationMillis = 200),
                                    ) {
                                        state.touchPos = value
                                    }
                                } finally {
                                    state.pendingTargetSpread = null
                                    state.isCurling = false
                                }
                            }
                        },
                    )
                }
        ) {
            // 1. Offscreen/recording layers: placed BEHIND the interactive layer with no-op semantics
            // Previous spread recorded offscreen
            if (currentSpreadIndex > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            prevSpreadLayer.record {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    SpreadItemContent(
                        spreadIndex = currentSpreadIndex - 1,
                        current = current,
                        isDualPage = isDualPage,
                        decorations = currentDecorations,
                        style = style,
                        activeElementId = null,
                        activeColor = activeColor,
                        pageGestures = noOpGestures,
                        paragraphSpacingPx = paragraphSpacingPx,
                        density = density,
                        resumedElementId = null,
                        isEInk = isEInk,
                        effectiveMarginPx = effectiveMarginPx,
                        blockPaddingPx = blockPaddingPx,
                        gutterDp = gutterDp,
                        backgroundColor = backgroundColor,
                        foldable = foldable,
                    )
                }
            }

            // Next spread recorded offscreen
            if (currentSpreadIndex + 1 < spreadCount) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            nextSpreadLayer.record {
                                this@drawWithContent.drawContent()
                            }
                        }
                ) {
                    SpreadItemContent(
                        spreadIndex = currentSpreadIndex + 1,
                        current = current,
                        isDualPage = isDualPage,
                        decorations = currentDecorations,
                        style = style,
                        activeElementId = null,
                        activeColor = activeColor,
                        pageGestures = noOpGestures,
                        paragraphSpacingPx = paragraphSpacingPx,
                        density = density,
                        resumedElementId = null,
                        isEInk = isEInk,
                        effectiveMarginPx = effectiveMarginPx,
                        blockPaddingPx = blockPaddingPx,
                        gutterDp = gutterDp,
                        backgroundColor = backgroundColor,
                        foldable = foldable,
                    )
                }
            }

            // 2. Current spread (interactive, rendered in front of offscreen layers)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        flipperOrigin = it.positionInWindow()
                        flipperWidth = it.size.width.toFloat()
                    }
                    .drawWithContent {
                        currentSpreadLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        if (!state.isCurling) {
                            drawContent()
                        }
                    }
            ) {
                SpreadItemContent(
                    spreadIndex = currentSpreadIndex,
                    current = current,
                    isDualPage = isDualPage,
                    decorations = currentDecorations,
                    style = style,
                    activeElementId = activeElementId,
                    activeColor = activeColor,
                    pageGestures = pageGestures,
                    paragraphSpacingPx = paragraphSpacingPx,
                    density = density,
                    resumedElementId = resumedElementId,
                    isEInk = isEInk,
                    effectiveMarginPx = effectiveMarginPx,
                    blockPaddingPx = blockPaddingPx,
                    gutterDp = gutterDp,
                    backgroundColor = backgroundColor,
                    foldable = foldable,
                )
            }

            // 3. Curl Canvas: purely visual overlay, drawn ONLY when curling, NO pointerInput
            if (state.isCurling) {
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawCurlFrame(
                                state = state,
                                currentSpreadIndex = currentSpreadIndex,
                                currentLayer = currentSpreadLayer,
                                nextLayer = nextSpreadLayer,
                                prevLayer = prevSpreadLayer,
                                pageSize = leafSize,
                                totalWidth = totalWidth,
                                totalHeight = totalHeight,
                                isDualPage = isDualPage,
                                backgroundColor = backgroundColor,
                                isEInk = isEInk,
                            )
                        }
                )
            }
        }
    }
}

/** Draws the animated curl frame reading state only during draw phase. */
private fun DrawScope.drawCurlFrame(
    state: PageCurlState,
    currentSpreadIndex: Int,
    currentLayer: GraphicsLayer,
    nextLayer: GraphicsLayer,
    prevLayer: GraphicsLayer,
    pageSize: Size,
    totalWidth: Float,
    totalHeight: Float,
    isDualPage: Boolean,
    backgroundColor: Color,
    isEInk: Boolean,
) {
    val touch = state.touchPos
    val corner = state.activeCorner
    val isFwd = state.isForward

    val curl = CurlGeometry.compute(
        pageSize = pageSize,
        corner = corner,
        touch = touch,
    )

    if (!curl.isCurlActive || curl.progress <= 0.001f || (state.pendingTargetSpread != null && state.pendingTargetSpread == currentSpreadIndex)) {
        drawLayer(currentLayer)
        return
    }

    val spineX = if (isDualPage) totalWidth / 2f else 0f

    // Populate reusable paths
    populatePolygonPath(curl.frontPolygon, state.frontPath)
    populatePolygonPath(curl.flapPolygon, state.flapPath)
    populatePolygonPath(curl.reflectedFlapPolygon, state.reflectedFlapPath)

    if (!isDualPage) {
        // --- SINGLE PAGE MODE ---
        // 1. Underneath page (next if fwd, curr if bwd)
        if (isFwd) {
            drawLayer(nextLayer)
        } else {
            drawLayer(currentLayer)
        }

        // 2. Soft cast shadow on under page along the fold falling toward -normal
        if (!isEInk && curl.progress < 0.99f) {
            drawFoldShadowQuad(
                start = curl.foldStart,
                end = curl.foldEnd,
                direction = -curl.foldNormal,
                width = 24.dp.toPx(),
                maxAlpha = 0.35f,
                state = state,
            )
        }

        // 3. Front of turning page (flat remaining portion)
        clipPath(state.frontPath) {
            if (isFwd) {
                drawLayer(currentLayer)
            } else {
                drawLayer(prevLayer)
            }
            if (!isEInk) {
                drawFoldShadowQuad(
                    start = curl.foldStart,
                    end = curl.foldEnd,
                    direction = curl.foldNormal,
                    width = 12.dp.toPx(),
                    maxAlpha = 0.15f,
                    state = state,
                )
            }
        }

        // 4. Back of turning page (flap)
        clipPath(state.reflectedFlapPath) {
            drawIntoCanvas { canvas ->
                canvas.save()
                curl.populateAndroidMatrix(state.androidMatrix)
                canvas.nativeCanvas.concat(state.androidMatrix)
                if (isFwd) {
                    drawLayer(currentLayer)
                } else {
                    drawLayer(prevLayer)
                }
                // Physical paper show-through blend (85% paper color)
                drawRect(color = backgroundColor.copy(alpha = 0.85f))
                canvas.restore()
            }
            if (!isEInk) {
                drawFoldShadowQuad(
                    start = curl.foldStart,
                    end = curl.foldEnd,
                    direction = curl.foldNormal,
                    width = 14.dp.toPx(),
                    maxAlpha = 0.22f,
                    state = state,
                )
                drawFoldHighlight(
                    start = curl.foldStart,
                    end = curl.foldEnd,
                    width = 2.5.dp.toPx(),
                    alpha = 0.25f,
                )
            }
        }
    } else {
        // --- TWO-PAGE SPREAD MODE ---
        // Leaf hinges at spine center spineX = totalWidth / 2f
        if (isFwd) {
            // Forward: Right page turns over center spine onto left page
            // 1. Base layer:
            // Left half shows stationary current left page
            drawLayer(currentLayer)

            // Right half shows underneath next right page
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.nativeCanvas.clipRect(spineX, 0f, totalWidth, totalHeight)
                drawLayer(nextLayer)
                canvas.restore()
            }

            // 2. Soft cast shadow on under right page along fold falling toward -normal
            if (!isEInk && curl.progress < 0.99f) {
                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.nativeCanvas.translate(spineX, 0f)
                    drawFoldShadowQuad(
                        start = curl.foldStart,
                        end = curl.foldEnd,
                        direction = -curl.foldNormal,
                        width = 24.dp.toPx(),
                        maxAlpha = 0.35f,
                        state = state,
                    )
                    canvas.restore()
                }
            }

            // 3. Turning Leaf Front (current right page)
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.nativeCanvas.translate(spineX, 0f)
                clipPath(state.frontPath) {
                    drawIntoCanvas { c ->
                        c.save()
                        c.nativeCanvas.translate(-spineX, 0f)
                        drawLayer(currentLayer)
                        c.restore()
                    }
                    if (!isEInk) {
                        drawFoldShadowQuad(
                            start = curl.foldStart,
                            end = curl.foldEnd,
                            direction = curl.foldNormal,
                            width = 12.dp.toPx(),
                            maxAlpha = 0.15f,
                            state = state,
                        )
                    }
                }
                canvas.restore()
            }

            // 4. Turning Leaf Flap (Back face = next spread left page, unmirrored, hinged at spineX)
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.nativeCanvas.translate(spineX, 0f)
                clipPath(state.reflectedFlapPath) {
                    canvas.save()
                    curl.populateAndroidMatrix(state.androidMatrix)
                    canvas.nativeCanvas.concat(state.androidMatrix)

                    // Un-mirror horizontally across leaf center
                    canvas.nativeCanvas.scale(-1f, 1f, pageSize.width / 2f, pageSize.height / 2f)
                    drawRect(color = backgroundColor)
                    drawLayer(nextLayer)
                    canvas.restore()

                    if (!isEInk) {
                        drawFoldShadowQuad(
                            start = curl.foldStart,
                            end = curl.foldEnd,
                            direction = curl.foldNormal,
                            width = 14.dp.toPx(),
                            maxAlpha = 0.22f,
                            state = state,
                        )
                        drawFoldHighlight(
                            start = curl.foldStart,
                            end = curl.foldEnd,
                            width = 2.5.dp.toPx(),
                            alpha = 0.25f,
                        )
                    }
                }
                canvas.restore()
            }
        } else {
            // Backward: Left page turns over center spine onto right page
            // 1. Base layer:
            // Right half shows stationary current right page
            drawLayer(currentLayer)

            // Left half shows underneath previous left page
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.nativeCanvas.clipRect(0f, 0f, spineX, totalHeight)
                drawLayer(prevLayer)
                canvas.restore()
            }

            // 2. Soft cast shadow on under left page falling toward -normal
            if (!isEInk && curl.progress < 0.99f) {
                drawFoldShadowQuad(
                    start = curl.foldStart,
                    end = curl.foldEnd,
                    direction = -curl.foldNormal,
                    width = 24.dp.toPx(),
                    maxAlpha = 0.35f,
                    state = state,
                )
            }

            // 3. Turning Leaf Front (current left page)
            clipPath(state.frontPath) {
                drawLayer(currentLayer)
                if (!isEInk) {
                    drawFoldShadowQuad(
                        start = curl.foldStart,
                        end = curl.foldEnd,
                        direction = curl.foldNormal,
                        width = 12.dp.toPx(),
                        maxAlpha = 0.15f,
                        state = state,
                    )
                }
            }

            // 4. Turning Leaf Flap (Back face = prev spread right page, unmirrored, hinged at spineX)
            clipPath(state.reflectedFlapPath) {
                drawIntoCanvas { canvas ->
                    canvas.save()
                    curl.populateAndroidMatrix(state.androidMatrix)
                    canvas.nativeCanvas.concat(state.androidMatrix)

                    // Un-mirror horizontally across leaf center
                    canvas.nativeCanvas.scale(-1f, 1f, pageSize.width / 2f, pageSize.height / 2f)
                    drawRect(color = backgroundColor)
                    // Shift so previous spread's right page lines up
                    canvas.nativeCanvas.translate(-spineX, 0f)
                    drawLayer(prevLayer)
                    canvas.restore()
                }

                if (!isEInk) {
                    drawFoldShadowQuad(
                        start = curl.foldStart,
                        end = curl.foldEnd,
                        direction = curl.foldNormal,
                        width = 14.dp.toPx(),
                        maxAlpha = 0.22f,
                        state = state,
                    )
                    drawFoldHighlight(
                        start = curl.foldStart,
                        end = curl.foldEnd,
                        width = 2.5.dp.toPx(),
                        alpha = 0.25f,
                    )
                }
            }
        }
    }
}

/** Populates a reusable Path from a list of polygon vertices. */
private fun populatePolygonPath(vertices: List<Offset>, path: Path) {
    path.reset()
    if (vertices.isEmpty()) return
    path.moveTo(vertices[0].x, vertices[0].y)
    for (i in 1 until vertices.size) {
        path.lineTo(vertices[i].x, vertices[i].y)
    }
    path.close()
}

/** Draws a one-sided soft shadow quad along the fold line with linear gradient falloff. */
private fun DrawScope.drawFoldShadowQuad(
    start: Offset,
    end: Offset,
    direction: Offset,
    width: Float,
    maxAlpha: Float,
    state: PageCurlState,
    color: Color = Color.Black,
) {
    if (width <= 0f || maxAlpha <= 0f) return
    val ox = direction.x * width
    val oy = direction.y * width

    state.shadowPath.reset()
    state.shadowPath.moveTo(start.x, start.y)
    state.shadowPath.lineTo(end.x, end.y)
    state.shadowPath.lineTo(end.x + ox, end.y + oy)
    state.shadowPath.lineTo(start.x + ox, start.y + oy)
    state.shadowPath.close()

    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    val gradEnd = mid + Offset(ox, oy)

    drawPath(
        path = state.shadowPath,
        brush = Brush.linearGradient(
            colors = listOf(color.copy(alpha = maxAlpha), Color.Transparent),
            start = mid,
            end = gradEnd,
        ),
    )
}

/** Draws thin specular crease highlight on the turning flap. */
private fun DrawScope.drawFoldHighlight(
    start: Offset,
    end: Offset,
    width: Float,
    alpha: Float,
) {
    drawLine(
        color = Color.White.copy(alpha = alpha),
        start = start,
        end = end,
        strokeWidth = width,
    )
}

/** Runs a 350ms synthetic curl for tap-zone or hardware-key turns. */
private suspend fun runSyntheticCurl(
    state: PageCurlState,
    isForward: Boolean,
    leafSize: Size,
    onFinished: () -> Unit,
) {
    val pageW = leafSize.width
    val pageH = leafSize.height
    val startX = if (isForward) pageW else 0f
    val endX = if (isForward) -pageW else 2f * pageW
    val startY = if (state.activeCorner.isBottomSide) pageH else 0f
    val endY = if (state.activeCorner.isBottomSide) (pageH - 40f).coerceAtLeast(0f) else 40f

    try {
        state.animatingOffset.snapTo(Offset(startX, startY))
        state.touchPos = Offset(startX, startY)
        state.animatingOffset.animateTo(
            targetValue = Offset(endX, endY),
            animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        ) {
            state.touchPos = value
        }
        state.pendingTargetSpread = null
        state.isCurling = false
        onFinished()
    } catch (_: CancellationException) {
        state.pendingTargetSpread = null
        state.isCurling = false
    }
}

/** Composable rendering a spread or page content at rest with tap detection. */
@Composable
private fun SpreadItemContent(
    spreadIndex: Int,
    current: List<ReaderPage>,
    isDualPage: Boolean,
    decorations: ParagraphDecorations,
    style: TextStyle,
    activeElementId: String?,
    activeColor: Color,
    pageGestures: ReaderTextGestures,
    paragraphSpacingPx: Float,
    density: Density,
    resumedElementId: String?,
    isEInk: Boolean,
    effectiveMarginPx: Float,
    blockPaddingPx: Float,
    gutterDp: Dp,
    backgroundColor: Color,
    foldable: FoldableLayoutInfo,
) {
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }

    if (isDualPage) {
        val leftPageIndex = spreadIndex * 2
        val rightPageIndex = spreadIndex * 2 + 1
        val leftPage = current.getOrNull(leftPageIndex)
        val rightPage = current.getOrNull(rightPageIndex)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
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
                ) {
                    if (leftPage != null) {
                        PageContent(
                            page = leftPage,
                            decorations = decorations,
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
                Spacer(
                    Modifier
                        .width(gutterDp)
                        .fillMaxHeight()
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (rightPage != null) {
                        PageContent(
                            page = rightPage,
                            decorations = decorations,
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
        val page = current.getOrNull(spreadIndex)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .onGloballyPositioned { pageOrigin = it.positionInWindow() }
                .pointerInput(pageGestures) { detectTapGestures { pageGestures.onTap(it + pageOrigin) } }
                .padding(
                    horizontal = with(density) { (effectiveMarginPx - blockPaddingPx).toDp() },
                )
                .padding(top = PageTopPadding, bottom = PageBottomPadding),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                with(density) { paragraphSpacingPx.toDp() }
            ),
        ) {
            if (page != null) {
                PageContent(
                    page = page,
                    decorations = decorations,
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
