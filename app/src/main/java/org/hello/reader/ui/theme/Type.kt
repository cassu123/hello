package org.hello.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.hello.reader.R

/** Serif for book titles, screen titles and reading. */
val Literata = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_medium, FontWeight.Medium),
    Font(R.font.literata_semibold, FontWeight.SemiBold),
    Font(R.font.literata_bold, FontWeight.Bold),
    Font(R.font.literata_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

/** Sans for every control, label and piece of metadata. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

val AtkinsonHyperlegible = FontFamily(
    Font(R.font.atkinson_regular, FontWeight.Normal),
    Font(R.font.atkinson_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.atkinson_bold, FontWeight.Bold),
)

val OpenDyslexic = FontFamily(
    Font(R.font.opendyslexic_regular, FontWeight.Normal),
    Font(R.font.opendyslexic_bold, FontWeight.Bold),
)

private fun inter(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private fun literata(size: Int, line: Int, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
    fontFamily = Literata,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (-0.01).em,
)

/**
 * Display and headline roles are Literata (screen titles, book titles); title, body and
 * label roles are Inter. `titleLarge` stays serif because it names books on detail screens.
 */
val HelloTypography = Typography(
    displayLarge = literata(44, 52),
    displayMedium = literata(36, 44),
    displaySmall = literata(32, 40),
    headlineLarge = literata(28, 36),
    headlineMedium = literata(24, 30),
    headlineSmall = literata(20, 26),
    titleLarge = literata(22, 28),
    titleMedium = inter(15, 20, FontWeight.SemiBold),
    titleSmall = inter(14, 20, FontWeight.SemiBold),
    bodyLarge = inter(16, 24, FontWeight.Normal),
    bodyMedium = inter(14, 20, FontWeight.Normal),
    bodySmall = inter(12, 16, FontWeight.Normal),
    labelLarge = inter(14, 20, FontWeight.Medium),
    labelMedium = inter(12, 16, FontWeight.Medium),
    labelSmall = inter(11, 14, FontWeight.Medium, tracking = 0.01),
)
