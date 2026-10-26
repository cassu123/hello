package org.hello.reader.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.hello.reader.core.model.Book
import org.hello.reader.ui.theme.CoverShape
import org.hello.reader.ui.theme.Literata
import kotlin.math.abs

/**
 * Book cover loaded through the app's authenticated image loader, over a typographic
 * placeholder (shown while loading, or for books with no cover art).
 */
@Composable
fun CoverImage(
    book: Book,
    modifier: Modifier = Modifier,
    shape: Shape = CoverShape,
    titleSize: TextUnit = 13.sp,
) {
    val context = LocalContext.current
    var loaded by remember(book.coverUrl) { mutableStateOf(false) }
    val request = remember(book.coverUrl) {
        ImageRequest.Builder(context)
            .data(book.coverUrl)
            .crossfade(true)
            .build()
    }

    Box(
        modifier
            .clip(shape)
            .border(0.5.dp, Color.Black.copy(alpha = 0.10f), shape),
    ) {
        if (!loaded) CoverPlaceholder(book, titleSize)
        if (book.coverUrl != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
            )
        }
    }
}

/** Muted cloth colors for placeholder covers, picked stably from the title. */
private val CoverCloths = listOf(
    Color(0xFF2E5E5A), Color(0xFF3E5C76), Color(0xFF6E2B2B), Color(0xFFB5654A),
    Color(0xFF5B4B3A), Color(0xFF6B7F5E), Color(0xFF1F3B4D), Color(0xFF8B7A4F),
    Color(0xFF7A5C7E), Color(0xFF4A4E69),
)

internal fun coverClothFor(title: String): Color = CoverCloths[abs(title.hashCode()) % CoverCloths.size]

@Composable
private fun CoverPlaceholder(book: Book, titleSize: TextUnit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(coverClothFor(book.title))
            .padding(10.dp),
    ) {
        Text(
            book.title,
            fontFamily = Literata,
            fontWeight = FontWeight.SemiBold,
            fontSize = titleSize,
            lineHeight = titleSize * 1.2f,
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        book.authors.firstOrNull()?.let { author ->
            Text(
                author.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = (titleSize.value * 0.62f).sp,
                letterSpacing = 0.08.em,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
