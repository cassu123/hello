package org.hello.reader.feature.reader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlStateTest {

    private val totalWidth = 600f
    private val totalHeight = 1000f
    private val leafWidth = 300f // Dual page half width

    @Test
    fun `drag delta with negative X resolves to forward turn`() {
        val state = PageCurlState(spreadCount = 5, isDualPage = true)
        val started = state.onDragStarted(
            downPos = Offset(450f, 800f),
            dragDelta = Offset(-15f, -5f),
            currentSpreadIndex = 1,
            totalHeight = totalHeight,
            leafWidth = leafWidth,
        )

        assertTrue(started)
        assertTrue(state.isCurling)
        assertTrue(state.isForward)
        assertEquals(CurlCorner.BOTTOM_RIGHT, state.activeCorner)
        assertEquals(2, state.pendingTargetSpread)
        // Synchronously initialized without stale 0 or previous turn coordinates:
        assertEquals(leafWidth - 15f, state.touchPos.x, 0.01f)
        assertEquals(totalHeight - 5f, state.touchPos.y, 0.01f)
    }

    @Test
    fun `drag delta with positive X resolves to backward turn from anywhere`() {
        val state = PageCurlState(spreadCount = 5, isDualPage = true)
        // User touching right side (500f) but swiping right (+20f) to go backward
        val started = state.onDragStarted(
            downPos = Offset(500f, 200f),
            dragDelta = Offset(20f, 0f),
            currentSpreadIndex = 2,
            totalHeight = totalHeight,
            leafWidth = leafWidth,
        )

        assertTrue(started)
        assertTrue(state.isCurling)
        assertFalse(state.isForward)
        assertEquals(CurlCorner.TOP_LEFT, state.activeCorner)
        assertEquals(1, state.pendingTargetSpread)
        // Starts at 0 + deltaX = 20f synchronously
        assertEquals(20f, state.touchPos.x, 0.01f)
        assertEquals(0f, state.touchPos.y, 0.01f)
    }

    @Test
    fun `cannot drag forward on last spread or backward on first spread`() {
        val state = PageCurlState(spreadCount = 5, isDualPage = true)

        // Backward on first spread (index 0)
        val backwardOnFirst = state.onDragStarted(
            downPos = Offset(200f, 500f),
            dragDelta = Offset(25f, 0f),
            currentSpreadIndex = 0,
            totalHeight = totalHeight,
            leafWidth = leafWidth,
        )
        assertFalse(backwardOnFirst)
        assertFalse(state.isCurling)

        // Forward on last spread (index 4)
        val forwardOnLast = state.onDragStarted(
            downPos = Offset(400f, 500f),
            dragDelta = Offset(-25f, 0f),
            currentSpreadIndex = 4,
            totalHeight = totalHeight,
            leafWidth = leafWidth,
        )
        assertFalse(forwardOnLast)
        assertFalse(state.isCurling)
    }

    @Test
    fun `synchronous drag updates accumulate without delay`() {
        val state = PageCurlState(spreadCount = 3, isDualPage = false)
        state.onDragStarted(
            downPos = Offset(500f, 800f),
            dragDelta = Offset(-10f, 0f),
            currentSpreadIndex = 0,
            totalHeight = totalHeight,
            leafWidth = 600f,
        )
        assertEquals(590f, state.touchPos.x, 0.01f)

        state.onDragDelta(Offset(-30f, 5f))
        assertEquals(560f, state.touchPos.x, 0.01f)
        assertEquals(totalHeight + 5f, state.touchPos.y, 0.01f)

        state.onDragDelta(Offset(-50f, -10f))
        assertEquals(510f, state.touchPos.x, 0.01f)
        assertEquals(totalHeight - 5f, state.touchPos.y, 0.01f)
    }

    @Test
    fun `shouldCompleteTurn honors halfway threshold and fling velocity`() {
        val state = PageCurlState(spreadCount = 3, isDualPage = true)
        state.isForward = true

        // Progress >= 0.40 with forward motion -> completes
        assertTrue(state.shouldCompleteTurn(progress = 0.45f, velocityX = -50f))
        // Progress >= 0.40 with backward release motion -> cancels
        assertFalse(state.shouldCompleteTurn(progress = 0.45f, velocityX = 50f))
        // Progress < 0.40 without fling -> cancels
        assertFalse(state.shouldCompleteTurn(progress = 0.25f, velocityX = -100f))
        // Progress < 0.40 with forward fling velocity -> completes
        assertTrue(state.shouldCompleteTurn(progress = 0.15f, velocityX = -700f))

        // Backward turn tests
        state.isForward = false
        assertTrue(state.shouldCompleteTurn(progress = 0.45f, velocityX = 50f))
        assertFalse(state.shouldCompleteTurn(progress = 0.45f, velocityX = -50f))
        assertTrue(state.shouldCompleteTurn(progress = 0.20f, velocityX = 750f))
    }
}
