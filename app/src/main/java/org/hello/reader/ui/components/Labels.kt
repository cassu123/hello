package org.hello.reader.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.hello.reader.R
import org.hello.reader.feature.library.LibraryFormatters

/**
 * Labels built from numbers, for code that assembles text outside composition (status lines,
 * pure functions under test). [rememberLabels] backs it with string resources.
 */
interface Labels {
    /** "2h 05m left", "12m left", "<1m left". */
    fun timeLeft(ms: Long): String
    fun timeLeftInChapter(ms: Long): String

    /** The chapter's own title, or "Chapter 3" for [index] 2. */
    fun chapter(index: Int, title: String?): String

    /** "Page 12 of 40" for [page] 11. */
    fun page(page: Int, pageCount: Int): String
}

private class ResourceLabels(private val resources: Resources) : Labels {
    override fun timeLeft(ms: Long): String {
        val (hours, minutes) = LibraryFormatters.hoursAndMinutes(ms)
        return when {
            hours > 0 -> resources.getString(R.string.time_left_hours_minutes, hours, minutes)
            minutes > 0 -> resources.getString(R.string.time_left_minutes, minutes)
            else -> resources.getString(R.string.time_left_under_minute)
        }
    }

    override fun timeLeftInChapter(ms: Long): String = resources.getString(R.string.time_left_in_chapter, timeLeft(ms))

    override fun chapter(index: Int, title: String?): String =
        title?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.chapter_number, index + 1)

    override fun page(page: Int, pageCount: Int): String = resources.getString(R.string.page_of, page + 1, pageCount)
}

@Composable
fun rememberLabels(): Labels {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { ResourceLabels(resources) }
}

/** The chapter's title, or "Chapter 3" for [index] 2. */
@Composable
fun chapterLabel(index: Int, title: String?): String = rememberLabels().chapter(index, title)
