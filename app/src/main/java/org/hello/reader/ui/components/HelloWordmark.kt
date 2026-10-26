package org.hello.reader.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hello.reader.R
import org.hello.reader.ui.theme.Literata

private val Baloo2 = FontFamily(Font(R.font.baloo2_extrabold, FontWeight.ExtraBold))

/** Brand coral for the wordmark's final "o"; matches the launcher icon's headphones. */
val HelloCoral = Color(0xFFF08A6C)

/** Per-letter tilt (degrees) and lift (fraction of the font size), cycled: the "waving" bounce. */
private val Bounce = listOf(-6f to 0.03f, 4f to -0.05f, -3f to 0f, 5f to -0.04f, -5f to 0.03f)

/**
 * Draws [word] as bouncy letters. The letter at [accentIndex] takes [accent]; punctuation stays upright.
 */
@Composable
private fun RowScope.BouncyLetters(
    word: String,
    fontSize: TextUnit,
    color: Color,
    accent: Color,
    accentIndex: Int,
) {
    val style = TextStyle(fontFamily = Baloo2, fontWeight = FontWeight.ExtraBold, fontSize = fontSize)
    var letterIndex = 0
    word.forEachIndexed { index, char ->
        val (tilt, lift) = if (char.isLetter()) Bounce[letterIndex++ % Bounce.size] else 0f to 0f
        Text(
            text = char.toString(),
            style = style,
            color = if (index == accentIndex) accent else color,
            modifier = Modifier
                .alignByBaseline()
                .graphicsLayer {
                    rotationZ = tilt
                    translationY = lift * fontSize.toPx()
                },
        )
    }
}

/**
 * The "hello" wordmark: rounded letters that tilt like they're waving, with a coral "o".
 * Read by screen readers as the app name, not letter by letter.
 */
@Composable
fun HelloWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 44.sp,
    color: Color = MaterialTheme.colorScheme.onBackground,
    accent: Color = HelloCoral,
) {
    val appName = stringResource(R.string.app_name)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = appName },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BouncyLetters("hello", fontSize, color, accent, accentIndex = 4)
    }
}

/**
 * The home greeting, e.g. "Hello, Sweetie": the part before the comma in bouncy letters (its last
 * letter in the theme's primary colour), the rest in the book serif, italic, over a hand-drawn
 * underline. Follows the active theme. Text without a comma falls back to a plain headline.
 */
@Composable
fun HelloGreeting(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 32.sp,
) {
    val comma = text.indexOf(',')
    if (comma <= 0) {
        Text(text, style = MaterialTheme.typography.headlineLarge, modifier = modifier)
        return
    }
    val greeting = text.substring(0, comma)
    val name = text.substring(comma + 1).trim().trimEnd('!', '.')
    val ink = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary
    val underlineWidth = with(LocalDensity.current) { 2.5.dp.toPx() }
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = text
            heading()
        },
    ) {
        BouncyLetters("$greeting,", fontSize, ink, accent, accentIndex = greeting.lastIndex)
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = TextStyle(
                fontFamily = Literata,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize * 0.92f,
            ),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .alignByBaseline()
                .drawWithContent {
                    drawContent()
                    // A loose, hand-drawn swash just below the baseline.
                    val w = size.width
                    val y = size.height * 0.94f
                    val swash = Path().apply {
                        moveTo(w * 0.02f, y)
                        cubicTo(w * 0.25f, y - 6.dp.toPx(), w * 0.5f, y + 3.dp.toPx(), w * 0.72f, y - 2.dp.toPx())
                        cubicTo(w * 0.82f, y - 4.dp.toPx(), w * 0.92f, y - 3.dp.toPx(), w * 0.99f, y - 1.dp.toPx())
                    }
                    drawPath(swash, accent, style = Stroke(width = underlineWidth, cap = StrokeCap.Round))
                },
        )
    }
}
