package org.hello.reader.feature.reader

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import org.hello.reader.core.model.ReaderFontFamily
import org.hello.reader.core.model.ReaderSettings
import org.hello.reader.ui.theme.AtkinsonHyperlegible
import org.hello.reader.ui.theme.Literata
import org.hello.reader.ui.theme.OpenDyslexic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.hello.reader.R

/** Result of resolving the reader's typeface for the current [ReaderSettings]. */
data class ReaderFont(
    val family: FontFamily,
    /** Non-null when a custom font was selected but couldn't be loaded. */
    val error: String? = null,
)

/**
 * Loads sideloaded `.ttf` / `.otf` fonts from their persisted SAF URI. Typefaces
 * are cached per URI for the process lifetime: the font file is memory-mapped
 * by the framework, so the descriptor can be closed right after building.
 */
object ReaderFontLoader {

    private const val TAG = "ReaderFontLoader"
    private val cache = ConcurrentHashMap<String, Typeface>()

    /** Performs file I/O; call from a background dispatcher. Returns null if the file isn't a usable font. */
    fun loadTypeface(context: Context, uri: String): Typeface? {
        cache[uri]?.let { return it }
        return try {
            context.contentResolver.openFileDescriptor(uri.toUri(), "r")?.use { pfd ->
                Typeface.Builder(pfd.fileDescriptor).build()
            }?.also { cache[uri] = it }
        } catch (e: SecurityException) {
            // Persisted permission was revoked (e.g. the providing app was uninstalled).
            Log.w(TAG, "No longer allowed to read custom font", e)
            null
        } catch (e: IOException) {
            Log.w(TAG, "Custom font file is unavailable", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Custom font URI is invalid", e)
            null
        }
    }

    suspend fun load(context: Context, uri: String): Typeface? =
        withContext(Dispatchers.IO) { loadTypeface(context.applicationContext, uri) }
}

/** Literata, Atkinson Hyperlegible and OpenDyslexic ship in res/font; Bookerly is proprietary, so it maps to the platform serif. */
internal fun builtInFontFamily(family: ReaderFontFamily): FontFamily = when (family) {
    ReaderFontFamily.ATKINSON_HYPERLEGIBLE -> AtkinsonHyperlegible
    ReaderFontFamily.LITERATA -> Literata
    ReaderFontFamily.BOOKERLY -> FontFamily.Serif
    ReaderFontFamily.OPEN_DYSLEXIC -> OpenDyslexic
    ReaderFontFamily.CUSTOM -> FontFamily.Default
}

@Composable
fun rememberReaderFont(settings: ReaderSettings): ReaderFont {
    val context = LocalContext.current
    val uri = settings.customFontUri.takeIf { settings.fontFamily == ReaderFontFamily.CUSTOM }
    var font by remember(settings.fontFamily, uri) {
        mutableStateOf(ReaderFont(builtInFontFamily(settings.fontFamily)))
    }
    LaunchedEffect(uri) {
        if (uri == null) return@LaunchedEffect
        val typeface = ReaderFontLoader.load(context, uri)
        font = if (typeface != null) {
            ReaderFont(FontFamily(typeface))
        } else {
            ReaderFont(FontFamily.Default, error = context.getString(R.string.custom_font_failed))
        }
    }
    return font
}
