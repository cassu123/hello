package org.hello.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.core.engine.smil.isChapterHeadingText

/** Callbacks for the three reader gestures on a paragraph. Offsets are within the paragraph's full text. */
interface ReaderTextGestures {
    /** [positionInWindow] lets the paged reader treat the screen edges as page-turn zones. */
    fun onTap(positionInWindow: Offset)
    fun onDoubleTap(sentence: ReaderSentenceItem)
    fun onSelectionStart(sentence: ReaderSentenceItem, offset: Int)
    fun onSelectionExtend(sentence: ReaderSentenceItem, offset: Int)

    /** [anchorInWindow] bounds the selected text, for positioning the action menu. */
    fun onSelectionEnd(sentence: ReaderSentenceItem, anchorInWindow: Rect)
}

/**
 * One paragraph (or, when paginated, a fragment of one starting at [textOffset]).
 * Gestures, in one detector so they can't fight each other:
 * - tap: toggle reader chrome
 * - double tap: start narration at this sentence
 * - long press: select the word under the finger; keep holding and drag to extend; release opens the menu
 * A long press is never also reported as a tap, and a scroll cancels everything.
 */
@Composable
fun ReaderTextBlock(
    sentence: ReaderSentenceItem,
    text: AnnotatedString,
    textOffset: Int,
    style: TextStyle,
    isActive: Boolean,
    activeColor: Color,
    selection: CharSpan?,
    gestures: ReaderTextGestures,
    modifier: Modifier = Modifier,
    isBookmarked: Boolean = false,
    /** Zero when paginated, so measured page heights match what is drawn. */
    verticalPadding: Dp = 4.dp,
    isResumed: Boolean = false,
    isEInk: Boolean = false,
) {
    if (sentence.text.isBlank()) return

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val haptics = LocalHapticFeedback.current
    val currentGestures by rememberUpdatedState(gestures)
    val currentSelection by rememberUpdatedState(selection)

    fun offsetAt(position: Offset): Int? = layout?.getOffsetForPosition(position)?.plus(textOffset)

    fun selectionBoundsInWindow(): Rect? {
        val result = layout ?: return null
        val coords = coordinates ?: return null
        val span = currentSelection ?: return null
        val start = (span.start - textOffset).coerceIn(0, result.layoutInput.text.length)
        val end = (span.end - textOffset).coerceIn(0, result.layoutInput.text.length)
        if (start >= end) return null
        val first = result.getBoundingBox(start)
        val last = result.getBoundingBox(end - 1)
        val local = Rect(
            left = minOf(first.left, last.left),
            top = first.top,
            right = maxOf(first.right, last.right),
            bottom = last.bottom,
        )
        val origin = coords.localToWindow(Offset.Zero)
        return local.translate(origin)
    }

    val isHeading = sentence.isChapterStart && textOffset == 0 && (
        (sentence.chapterTitle != null && sentence.text.trim().equals(sentence.chapterTitle.trim(), ignoreCase = true)) ||
        isChapterHeadingText(sentence.text)
    )
    val effectiveStyle = if (isHeading) {
        style.copy(
            fontWeight = FontWeight.Bold,
            fontSize = style.fontSize * 1.15f,
        )
    } else {
        style
    }

    val backgroundModifier = when {
        isActive -> Modifier.background(activeColor.copy(alpha = 0.38f))
        isResumed && !isEInk -> Modifier.background(activeColor.copy(alpha = 0.16f))
        else -> Modifier
    }

    val resumeBarModifier = if (isResumed) {
        Modifier.drawBehind {
            val barColor = if (isEInk) Color.Black else activeColor
            val barWidth = if (isEInk) 4.dp.toPx() else 3.5.dp.toPx()
            drawRect(
                color = barColor,
                topLeft = Offset.Zero,
                size = Size(barWidth, size.height),
            )
        }
    } else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(backgroundModifier)
            .then(resumeBarModifier)
            .padding(horizontal = 10.dp, vertical = verticalPadding),
    ) {
        if (isBookmarked) {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = stringResource(R.string.bookmarked),
                tint = activeColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp)
                    .size(16.dp),
            )
        }
        Text(
            text = text,
            style = effectiveStyle,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates = it }
                .pointerInput(sentence.elementId, textOffset) {
                    detectReaderGestures(
                        onTap = { pos ->
                            val origin = coordinates?.localToWindow(Offset.Zero) ?: Offset.Zero
                            currentGestures.onTap(pos + origin)
                        },
                        onDoubleTap = { currentGestures.onDoubleTap(sentence) },
                        onLongPress = { pos ->
                            val offset = offsetAt(pos) ?: return@detectReaderGestures false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentGestures.onSelectionStart(sentence, offset)
                            true
                        },
                        onDrag = { pos -> offsetAt(pos)?.let { currentGestures.onSelectionExtend(sentence, it) } },
                        onRelease = {
                            selectionBoundsInWindow()?.let { currentGestures.onSelectionEnd(sentence, it) }
                        },
                    )
                },
        )
    }
}

/**
 * Hand-rolled because `detectTapGestures` reports a released long press as a
 * tap when no long-press handler consumes it, and combining it with
 * `detectDragGesturesAfterLongPress` makes the two detectors race for the same
 * pointer. Consumption checks in the Final pass let a parent scroll cancel us.
 */
private suspend fun PointerInputScope.detectReaderGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: () -> Unit,
    /** Return false if nothing selectable is under the finger (gesture then ends). */
    onLongPress: (Offset) -> Boolean,
    onDrag: (Offset) -> Unit,
    onRelease: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    // Distinguish "timed out while held" (long press) from "cancelled by a scroll".
    var cancelled = false
    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        waitForUpOrCancellation().also { if (it == null) cancelled = true }
    }
    if (cancelled) return@awaitEachGesture

    if (up != null) {
        val distance = (up.position - down.position).getDistance()
        if (distance <= viewConfiguration.touchSlop) {
            up.consume()
            val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
            if (secondDown == null) {
                onTap(down.position)
            } else {
                waitForUpOrCancellation()?.let {
                    it.consume()
                    onDoubleTap()
                }
            }
        }
        return@awaitEachGesture
    }

    if (!onLongPress(down.position)) return@awaitEachGesture
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            change.consume()
            break
        }
        if (change.positionChanged()) onDrag(change.position)
        // Keep the list from scrolling while the user drags a selection.
        change.consume()
    }
    onRelease()
}
