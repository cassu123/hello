package org.hello.reader.feature.library

import java.util.Locale

object LibraryFormatters {

    /** Whole hours and the minutes past them, for "2h 05m left" (see [org.hello.reader.ui.components.Labels]). */
    fun hoursAndMinutes(ms: Long): Pair<Long, Long> {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return totalMinutes / 60 to totalMinutes % 60
    }

    /** Clock-style playback position: "1:02:03" or "4:05". */
    fun position(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    fun percent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"

    /** Formats byte counts into human-readable representations: "124 MB", "1.2 GB", etc. */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.ROOT, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.ROOT, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.ROOT, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    /** Formats a playback speed multiplier such as "1.00×" or (when [trimTrailingZeros] is true) "1×". */
    fun speed(speed: Float, trimTrailingZeros: Boolean = false): String {
        val base = String.format(Locale.US, "%.2f", speed)
        val formatted = if (trimTrailingZeros) base.trimEnd('0').trimEnd('.') else base
        return "${formatted}×"
    }
}
