package org.hello.reader.feature.reader

import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.sqrt

/** Corner from which a curl drag or animation initiates. */
enum class CurlCorner {
    BOTTOM_RIGHT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    TOP_LEFT;

    val isRightSide: Boolean get() = this == BOTTOM_RIGHT || this == TOP_RIGHT
    val isBottomSide: Boolean get() = this == BOTTOM_RIGHT || this == BOTTOM_LEFT
}

/** Identifies which page of the viewport is being lifted/turned. */
enum class TurningPageSide {
    SINGLE,
    SPREAD_RIGHT,
    SPREAD_LEFT,
}

/**
 * Result of computing the 2D page-curl geometry.
 *
 * @property progress Turn progress from 0f (rest) to 1f (fully turned).
 * @property isCurlActive True if a curl is actively deforming the page.
 * @property foldStart One endpoint of the fold line on the page boundaries.
 * @property foldEnd The other endpoint of the fold line on the page boundaries.
 * @property foldNormal Unit vector perpendicular to the fold line pointing toward the flap.
 * @property frontPolygon Vertices of the remaining flat portion of the turning page.
 * @property flapPolygon Vertices of the unreflected flap on the turning page.
 * @property reflectedFlapPolygon Vertices of the flap reflected across the fold line.
 * @property reflectionMatrix Affine 2D reflection matrix values [a, b, c, d, e, f, 0, 0, 1] mapping points across the fold line (C -> T).
 */
data class CurlResult(
    val progress: Float,
    val isCurlActive: Boolean,
    val foldStart: Offset,
    val foldEnd: Offset,
    val foldNormal: Offset,
    val frontPolygon: List<Offset>,
    val flapPolygon: List<Offset>,
    val reflectedFlapPolygon: List<Offset>,
    val reflectionMatrix: FloatArray,
) {
    /** Transforms a 2D point using this reflection matrix. */
    fun mapPoint(point: Offset): Offset {
        val rx = reflectionMatrix[0] * point.x + reflectionMatrix[1] * point.y + reflectionMatrix[2]
        val ry = reflectionMatrix[3] * point.x + reflectionMatrix[4] * point.y + reflectionMatrix[5]
        return Offset(rx, ry)
    }

    /** Populates an [android.graphics.Matrix] with these reflection values. */
    fun populateAndroidMatrix(out: Matrix) {
        out.setValues(reflectionMatrix)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CurlResult) return false
        return progress == other.progress &&
            isCurlActive == other.isCurlActive &&
            foldStart == other.foldStart &&
            foldEnd == other.foldEnd &&
            foldNormal == other.foldNormal &&
            frontPolygon == other.frontPolygon &&
            flapPolygon == other.flapPolygon &&
            reflectedFlapPolygon == other.reflectedFlapPolygon &&
            reflectionMatrix.contentEquals(other.reflectionMatrix)
    }

    override fun hashCode(): Int {
        var result = progress.hashCode()
        result = 31 * result + isCurlActive.hashCode()
        result = 31 * result + foldStart.hashCode()
        result = 31 * result + foldEnd.hashCode()
        result = 31 * result + foldNormal.hashCode()
        result = 31 * result + frontPolygon.hashCode()
        result = 31 * result + flapPolygon.hashCode()
        result = 31 * result + reflectedFlapPolygon.hashCode()
        result = 31 * result + reflectionMatrix.contentHashCode()
        return result
    }
}

/**
 * Mathematical engine for realistic 2D book page curls.
 *
 * Models paper as an inextensible sheet attached to a spine:
 * - Corner C = initial corner of the turning leaf.
 * - Touch T = dragged corner position, clamped so |T - spineCorner| <= pageWidth.
 * - Fold line = perpendicular bisector of C -> T.
 * - Front polygon = clipped to the flat side of the fold line.
 * - Flap polygon = clipped to the lifted side of the fold line, then reflected across it.
 * - Reflection matrix maps C strictly to T.
 */
object CurlGeometry {

    private val IDENTITY_MATRIX = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    /** Determines which page to curl depending on layout and turn direction. */
    fun pickTurningPage(isDualPage: Boolean, isForward: Boolean): TurningPageSide = when {
        !isDualPage -> TurningPageSide.SINGLE
        isForward -> TurningPageSide.SPREAD_RIGHT
        else -> TurningPageSide.SPREAD_LEFT
    }

    /**
     * Computes the curl geometry for a turning page with dimensions [pageSize].
     *
     * Coordinates are local to the turning page:
     * - For right page or single page: spine is at x = 0, outer edge is at x = width.
     * - For left page in backward turn: spine is at x = width, outer edge is at x = 0.
     */
    fun compute(
        pageSize: Size,
        corner: CurlCorner,
        touch: Offset,
    ): CurlResult {
        val width = pageSize.width
        val height = pageSize.height
        if (width <= 0f || height <= 0f) {
            return emptyResult()
        }

        val isRight = corner.isRightSide
        val isBottom = corner.isBottomSide

        val cornerX = if (isRight) width else 0f
        val cornerY = if (isBottom) height else 0f
        val cornerPoint = Offset(cornerX, cornerY)

        val spineX = if (isRight) 0f else width

        // 1. Horizontal bounds:
        // A forward turn (isRight) starts at cornerX = width and flips over spine at x = 0 to -width.
        // A backward turn (!isRight) starts at cornerX = 0 and flips over spine at x = width to 2 * width.
        val rawX = if (isRight) {
            touch.x.coerceIn(-width, width)
        } else {
            touch.x.coerceIn(0f, 2f * width)
        }

        // Relative x from the spine (range [-width, width] where corner is at +width for right, -width for left)
        val xRelFromSpine = if (isRight) rawX else (rawX - width)

        // 2. Compute turn progress based on horizontal travel:
        // 0.0 = at rest (outer edge), 1.0 = fully turned flat onto the opposite page
        val progress = if (isRight) {
            ((width - rawX) / (2f * width)).coerceIn(0f, 1f)
        } else {
            (rawX / (2f * width)).coerceIn(0f, 1f)
        }

        // 3. Spine-hinge vertical constraint:
        // The perpendicular bisector between cornerPoint C and touch T is the fold line.
        // For the fold line never to intersect the interior of the vertical spine segment [0, height],
        // the vertical displacement |deltaY| from cornerPoint must not exceed sqrt(width^2 - xRelFromSpine^2).
        // In addition, as the page reaches the spine and turns flat onto the opposite page (progress -> 1.0),
        // the vertical displacement naturally attenuates to 0 so the leaf lands perfectly aligned.
        val maxSpineDy = sqrt((width * width - xRelFromSpine * xRelFromSpine).coerceAtLeast(0f))
        val paperStiffnessDy = (width * 0.40f * kotlin.math.sin(Math.PI.toFloat() * progress)).coerceAtLeast(0f)
        val allowedDy = minOf(maxSpineDy * 0.85f, paperStiffnessDy)

        val rawDy = touch.y - cornerY
        val clampedDy = if (isBottom) {
            // Dragging bottom corner upwards: rawDy <= 0 (cannot stretch paper below page bottom)
            if (rawDy < 0f) (-minOf(-rawDy, allowedDy)) else 0f
        } else {
            // Dragging top corner downwards: rawDy >= 0 (cannot stretch paper above page top)
            if (rawDy > 0f) minOf(rawDy, allowedDy) else 0f
        }

        val clampedTouch = Offset(rawX, cornerY + clampedDy)

        val fullRect = listOf(
            Offset(0f, 0f),
            Offset(width, 0f),
            Offset(width, height),
            Offset(0f, height),
        )

        // Boundary cases: at rest or fully turned
        if (progress <= 0.001f) {
            return CurlResult(
                progress = 0f,
                isCurlActive = false,
                foldStart = Offset.Zero,
                foldEnd = Offset.Zero,
                foldNormal = Offset.Zero,
                frontPolygon = fullRect,
                flapPolygon = emptyList(),
                reflectedFlapPolygon = emptyList(),
                reflectionMatrix = IDENTITY_MATRIX,
            )
        }

        if (progress >= 0.999f) {
            val spineMatrix = if (isRight) {
                // Reflection across x = 0: x -> -x
                floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            } else {
                // Reflection across x = width: x -> 2 * width - x
                floatArrayOf(-1f, 0f, 2f * width, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            val reflected = fullRect.map { p ->
                if (isRight) Offset(-p.x, p.y) else Offset(2f * width - p.x, p.y)
            }
            return CurlResult(
                progress = 1f,
                isCurlActive = true,
                foldStart = Offset(spineX, 0f),
                foldEnd = Offset(spineX, height),
                foldNormal = if (isRight) Offset(-1f, 0f) else Offset(1f, 0f),
                frontPolygon = emptyList(),
                flapPolygon = fullRect,
                reflectedFlapPolygon = reflected,
                reflectionMatrix = spineMatrix,
            )
        }

        // 3. Vector from corner C to touch T
        val vx = clampedTouch.x - cornerPoint.x
        val vy = clampedTouch.y - cornerPoint.y
        val len = sqrt(vx * vx + vy * vy)
        if (len < 0.001f) {
            return CurlResult(
                progress = 0f,
                isCurlActive = false,
                foldStart = Offset.Zero,
                foldEnd = Offset.Zero,
                foldNormal = Offset.Zero,
                frontPolygon = fullRect,
                flapPolygon = emptyList(),
                reflectedFlapPolygon = emptyList(),
                reflectionMatrix = IDENTITY_MATRIX,
            )
        }

        val nx = vx / len
        val ny = vy / len
        val midX = (cornerPoint.x + clampedTouch.x) / 2f
        val midY = (cornerPoint.y + clampedTouch.y) / 2f
        val normal = Offset(nx, ny)

        // 4. Closed-form 2D reflection matrix across fold line:
        // P' = P - 2 * ((P - M) . n) * n
        val a = 1f - 2f * nx * nx
        val b = -2f * nx * ny
        val c = 2f * nx * (nx * midX + ny * midY)
        val d = -2f * nx * ny
        val e = 1f - 2f * ny * ny
        val f = 2f * ny * (nx * midX + ny * midY)

        val matrixValues = floatArrayOf(
            a, b, c,
            d, e, f,
            0f, 0f, 1f,
        )

        // 5. Sutherland-Hodgman polygon clipping against fold line
        fun dist(p: Offset): Float = (p.x - midX) * nx + (p.y - midY) * ny

        val front = mutableListOf<Offset>()
        val flap = mutableListOf<Offset>()
        val intersections = mutableListOf<Offset>()

        // Clip against front half-plane (dist >= 0)
        for (i in fullRect.indices) {
            val curr = fullRect[i]
            val next = fullRect[(i + 1) % fullRect.size]
            val dCurr = dist(curr)
            val dNext = dist(next)

            if (dCurr >= 0f) {
                front.add(curr)
                if (dNext < 0f) {
                    val t = dCurr / (dCurr - dNext)
                    val inter = Offset(curr.x + t * (next.x - curr.x), curr.y + t * (next.y - curr.y))
                    front.add(inter)
                    intersections.add(inter)
                }
            } else {
                if (dNext >= 0f) {
                    val t = dCurr / (dCurr - dNext)
                    val inter = Offset(curr.x + t * (next.x - curr.x), curr.y + t * (next.y - curr.y))
                    front.add(inter)
                    intersections.add(inter)
                }
            }
        }

        // Clip against flap half-plane (dist <= 0)
        for (i in fullRect.indices) {
            val curr = fullRect[i]
            val next = fullRect[(i + 1) % fullRect.size]
            val dCurr = dist(curr)
            val dNext = dist(next)

            if (dCurr <= 0f) {
                flap.add(curr)
                if (dNext > 0f) {
                    val t = dCurr / (dCurr - dNext)
                    val inter = Offset(curr.x + t * (next.x - curr.x), curr.y + t * (next.y - curr.y))
                    flap.add(inter)
                }
            } else {
                if (dNext <= 0f) {
                    val t = dCurr / (dCurr - dNext)
                    val inter = Offset(curr.x + t * (next.x - curr.x), curr.y + t * (next.y - curr.y))
                    flap.add(inter)
                }
            }
        }

        // Reflected flap vertices
        val reflectedFlap = flap.map { p ->
            val rx = a * p.x + b * p.y + c
            val ry = d * p.x + e * p.y + f
            Offset(rx, ry)
        }

        val foldStart = intersections.getOrElse(0) { Offset(midX, midY) }
        val foldEnd = intersections.getOrElse(1) { Offset(midX, midY) }

        return CurlResult(
            progress = progress,
            isCurlActive = true,
            foldStart = foldStart,
            foldEnd = foldEnd,
            foldNormal = normal,
            frontPolygon = front,
            flapPolygon = flap,
            reflectedFlapPolygon = reflectedFlap,
            reflectionMatrix = matrixValues,
        )
    }

    private fun emptyResult() = CurlResult(
        progress = 0f,
        isCurlActive = false,
        foldStart = Offset.Zero,
        foldEnd = Offset.Zero,
        foldNormal = Offset.Zero,
        frontPolygon = emptyList(),
        flapPolygon = emptyList(),
        reflectedFlapPolygon = emptyList(),
        reflectionMatrix = IDENTITY_MATRIX,
    )
}
