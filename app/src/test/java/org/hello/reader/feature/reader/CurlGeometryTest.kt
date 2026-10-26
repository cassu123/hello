package org.hello.reader.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CurlGeometryTest {

    private val pageSize = Size(400f, 800f)

    @Test
    fun `progress 0 gives no flap`() {
        val result = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = Offset(400f, 800f),
        )
        assertEquals(0f, result.progress, 0.001f)
        assertFalse(result.isCurlActive)
        assertTrue(result.flapPolygon.isEmpty())
        assertTrue(result.reflectedFlapPolygon.isEmpty())
        assertEquals(4, result.frontPolygon.size)
    }

    @Test
    fun `full drag gives flap covering whole page`() {
        // Dragging right corner all the way across to -400f (full turn)
        val result = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = Offset(-400f, 800f),
        )
        assertEquals(1f, result.progress, 0.001f)
        assertTrue(result.isCurlActive)
        assertTrue(result.frontPolygon.isEmpty())
        assertEquals(4, result.flapPolygon.size)
        assertEquals(4, result.reflectedFlapPolygon.size)
        // Reflected flap should now cover [-400, 0] in x
        val minX = result.reflectedFlapPolygon.minOf { it.x }
        val maxX = result.reflectedFlapPolygon.maxOf { it.x }
        assertEquals(-400f, minX, 0.01f)
        assertEquals(0f, maxX, 0.01f)
    }

    @Test
    fun `spine clamp holds when dragged beyond page width from spine`() {
        // Spine corner for BOTTOM_RIGHT is (0, 800).
        // Try dragging way out to (-200, 1400) which has dist = sqrt(200^2 + 600^2) ~ 632 > 400
        val overdragTouch = Offset(-200f, 1400f)
        val result = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = overdragTouch,
        )

        val mappedCorner = result.mapPoint(Offset(400f, 800f))
        val distToSpine = sqrt(mappedCorner.x * mappedCorner.x + (mappedCorner.y - 800f) * (mappedCorner.y - 800f))
        assertTrue("Distance to spine corner ($distToSpine) must not exceed page width (400)", distToSpine <= 400.05f)
        assertTrue(result.isCurlActive)
        assertTrue(result.progress in 0f..1f)
    }

    @Test
    fun `reflection maps C to T`() {
        val corner = Offset(400f, 800f)
        val touch = Offset(200f, 750f)
        val result = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = touch,
        )

        // Reflection matrix applied to (400, 800) must equal touch
        val mapped = result.mapPoint(corner)
        assertEquals(touch.x, mapped.x, 0.05f)
        assertEquals(touch.y, mapped.y, 0.05f)
    }

    @Test
    fun `drag far past the page edge commits turn to 1 and stays clamped at maximum without tearing`() {
        val result = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = Offset(-1200f, 800f),
        )
        assertEquals(1f, result.progress, 0.001f)
        assertTrue(result.isCurlActive)
        assertTrue(result.frontPolygon.isEmpty())
        assertEquals(Offset(0f, 0f), result.foldStart)
        assertEquals(Offset(0f, 800f), result.foldEnd)
    }

    @Test
    fun `drag with extreme vertical offset never tears spine`() {
        // Dragging with large vertical offset far below the page
        val resultBelow = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = Offset(100f, 1500f),
        )
        assertTrue(resultBelow.isCurlActive)
        // Fold line endpoints must not cut into spine segment (0, 0)..(0, 800)
        // For BOTTOM_RIGHT, fold line intersects the spine line at or below y = 800
        val frontPoints = resultBelow.frontPolygon
        // Spine top (0, 0) and bottom (0, 800) must be retained on front side
        assertTrue(frontPoints.any { it.x == 0f && it.y == 0f })

        // Dragging with large vertical offset far above the page for TOP_RIGHT
        val resultAbove = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.TOP_RIGHT,
            touch = Offset(100f, -500f),
        )
        assertTrue(resultAbove.isCurlActive)
        // Spine bottom (0, 800) must be retained on front side
        assertTrue(resultAbove.frontPolygon.any { it.x == 0f && it.y == 800f })
    }

    @Test
    fun `backward turn dragged far past page edge commits cleanly`() {
        val result = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_LEFT,
            touch = Offset(1200f, 800f),
        )
        assertEquals(1f, result.progress, 0.001f)
        assertTrue(result.isCurlActive)
        assertTrue(result.frontPolygon.isEmpty())
        assertEquals(Offset(400f, 0f), result.foldStart)
        assertEquals(Offset(400f, 800f), result.foldEnd)
    }

    @Test
    fun `forward and backward turns are symmetric`() {
        val deltaX = 150f
        val deltaY = -60f

        // Forward turn from BOTTOM_RIGHT (400, 800) with delta
        val fwdResult = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_RIGHT,
            touch = Offset(400f - deltaX, 800f + deltaY),
        )

        // Backward turn from BOTTOM_LEFT (0, 800) with mirror delta
        val bwdResult = CurlGeometry.compute(
            pageSize = pageSize,
            corner = CurlCorner.BOTTOM_LEFT,
            touch = Offset(0f + deltaX, 800f + deltaY),
        )

        // Progress must be identical
        assertEquals(fwdResult.progress, bwdResult.progress, 0.001f)
        // Number of vertices in flap and front must match
        assertEquals(fwdResult.frontPolygon.size, bwdResult.frontPolygon.size)
        assertEquals(fwdResult.flapPolygon.size, bwdResult.flapPolygon.size)
    }

    @Test
    fun `spread mode picks the right page for forward and left page for backward`() {
        assertEquals(
            TurningPageSide.SPREAD_RIGHT,
            CurlGeometry.pickTurningPage(isDualPage = true, isForward = true),
        )
        assertEquals(
            TurningPageSide.SPREAD_LEFT,
            CurlGeometry.pickTurningPage(isDualPage = true, isForward = false),
        )
        assertEquals(
            TurningPageSide.SINGLE,
            CurlGeometry.pickTurningPage(isDualPage = false, isForward = true),
        )
        assertEquals(
            TurningPageSide.SINGLE,
            CurlGeometry.pickTurningPage(isDualPage = false, isForward = false),
        )
    }
}
