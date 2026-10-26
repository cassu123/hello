package org.hello.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing scale (4dp grid). Every screen uses [Gutter] as its horizontal padding. */
object Spacing {
    val XXS = 4.dp
    val XS = 8.dp
    val S = 12.dp
    val M = 16.dp
    val Gutter = 20.dp
    val L = 24.dp
    val XL = 32.dp

    /** Gap between sections on a scrolling screen. */
    val Section = 28.dp

    /** Minimum touch target. */
    val Touch = 44.dp
}

/** Corner radii. Covers are nearly square-cornered; controls, cards and sheets step up. */
object Radius {
    val Cover = 4.dp
    val Small = 8.dp
    val Control = 12.dp
    val Card = 16.dp
    val Sheet = 28.dp
}

val HelloShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.Cover),
    small = RoundedCornerShape(Radius.Small),
    medium = RoundedCornerShape(Radius.Control),
    large = RoundedCornerShape(Radius.Card),
    extraLarge = RoundedCornerShape(Radius.Sheet),
)

val CoverShape = RoundedCornerShape(Radius.Cover)
