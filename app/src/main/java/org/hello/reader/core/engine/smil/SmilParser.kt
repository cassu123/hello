package org.hello.reader.core.engine.smil

import kotlin.math.roundToLong

/**
 * Represents a single synchronized `<par>` clip inside an EPUB 3 SMIL 3 Media Overlay document.
 */
data class SmilParNode(
    val id: String,
    val textHref: String,
    val fragmentId: String,
    val audioSrc: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
) {
    init {
        require(clipEndMs >= clipBeginMs) {
            "clipEndMs ($clipEndMs) must be >= clipBeginMs ($clipBeginMs)"
        }
    }
}

/**
 * Parses Storyteller EPUB 3 SMIL 3 Media Overlay XML documents into ordered [SmilParNode] items
 * and parses W3C SMIL 3 clock values (`hh:mm:ss.fraction`, `mm:ss.fraction`, `s`, `ms`, `min`, `h`).
 *
 * Uses pure Kotlin regex/state extraction so it runs identically on Android and JVM unit tests
 * without requiring an Android `XmlPullParser` stub.
 */
object SmilParser {

    private val parBlockRegex = Regex(
        pattern = """<par\b([^>]*)>(.*?)</par>""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val textTagRegex = Regex(
        pattern = """<text\b([^>]*)/?>""",
        option = RegexOption.IGNORE_CASE,
    )
    private val audioTagRegex = Regex(
        pattern = """<audio\b([^>]*)/?>""",
        option = RegexOption.IGNORE_CASE,
    )
    private val attrRegex = Regex(
        pattern = """([a-zA-Z0-9_:-]+)\s*=\s*["']([^"']*)["']""",
    )

    fun parse(smilXml: String): List<SmilParNode> {
        val results = mutableListOf<SmilParNode>()
        var autoIndex = 0

        for (parMatch in parBlockRegex.findAll(smilXml)) {
            val parAttrs = parseAttributes(parMatch.groupValues[1])
            val innerXml = parMatch.groupValues[2]

            val textAttrs = textTagRegex.find(innerXml)?.let { parseAttributes(it.groupValues[1]) }
                ?: continue
            val audioAttrs = audioTagRegex.find(innerXml)?.let { parseAttributes(it.groupValues[1]) }
                ?: continue

            val rawTextSrc = textAttrs["src"]?.trim().orEmpty()
            if (rawTextSrc.isEmpty()) continue

            val textHref = rawTextSrc.substringBefore('#')
            val fragmentId = rawTextSrc.substringAfter('#', missingDelimiterValue = "")
            if (fragmentId.isEmpty()) continue

            val audioSrc = audioAttrs["src"]?.trim().orEmpty()
            if (audioSrc.isEmpty()) continue

            val clipBeginMs = parseClockValueMs(audioAttrs["clipBegin"] ?: audioAttrs["clip-begin"] ?: "0s")
            val clipEndMs = parseClockValueMs(audioAttrs["clipEnd"] ?: audioAttrs["clip-end"] ?: "0s")
                .coerceAtLeast(clipBeginMs)

            val parId = parAttrs["id"] ?: "par_${++autoIndex}"
            results += SmilParNode(
                id = parId,
                textHref = textHref,
                fragmentId = fragmentId,
                audioSrc = audioSrc,
                clipBeginMs = clipBeginMs,
                clipEndMs = clipEndMs,
            )
        }

        return results.sortedWith(compareBy({ it.audioSrc }, { it.clipBeginMs }))
    }

    /**
     * Parses a SMIL 3 clock value into milliseconds:
     * - Full clock: `01:02:03.450`
     * - Partial clock: `02:03.450`
     * - Timecount: `12.5s`, `250ms`, `2.5min`, `1h`, or bare seconds `4.2`
     */
    fun parseClockValueMs(raw: String): Long {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return 0L

        return when {
            value.endsWith("ms") -> value.removeSuffix("ms").trim().toDoubleOrNull()?.roundToLong() ?: 0L
            value.endsWith("min") -> ((value.removeSuffix("min").trim().toDoubleOrNull() ?: 0.0) * 60_000.0).roundToLong()
            value.endsWith("h") -> ((value.removeSuffix("h").trim().toDoubleOrNull() ?: 0.0) * 3_600_000.0).roundToLong()
            value.endsWith("s") -> ((value.removeSuffix("s").trim().toDoubleOrNull() ?: 0.0) * 1_000.0).roundToLong()
            value.contains(':') -> {
                val parts = value.split(':')
                when (parts.size) {
                    3 -> {
                        val hours = parts[0].toLongOrNull() ?: 0L
                        val minutes = parts[1].toLongOrNull() ?: 0L
                        val seconds = parts[2].toDoubleOrNull() ?: 0.0
                        hours * 3_600_000L + minutes * 60_000L + (seconds * 1_000.0).roundToLong()
                    }
                    2 -> {
                        val minutes = parts[0].toLongOrNull() ?: 0L
                        val seconds = parts[1].toDoubleOrNull() ?: 0.0
                        minutes * 60_000L + (seconds * 1_000.0).roundToLong()
                    }
                    else -> 0L
                }
            }
            else -> ((value.toDoubleOrNull() ?: 0.0) * 1_000.0).roundToLong()
        }.coerceAtLeast(0L)
    }

    private fun parseAttributes(attrText: String): Map<String, String> =
        attrRegex.findAll(attrText).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
}
