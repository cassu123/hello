package org.hello.reader.core.engine.audio

/**
 * Calculates the smart rewind offset in milliseconds based on how long playback
 * has been paused (`ARCHITECTURE.md` Section 6.3):
 * - `< 5s`: accidental pause -> `0ms`
 * - `5s ..< 1 min`: `2,000ms` (2s)
 * - `1 min ..< 30 mins`: `10,000ms` (10s)
 * - `>= 30 mins`: `30,000ms` (30s)
 */
object SmartRewindPolicy {

    fun calculateRewindMs(pauseDurationMs: Long): Long {
        return when {
            pauseDurationMs < 5_000L -> 0L
            pauseDurationMs < 60_000L -> 2_000L
            pauseDurationMs < 30 * 60_000L -> 10_000L
            else -> 30_000L
        }
    }

    fun applySmartRewind(currentPositionMs: Long, pauseDurationMs: Long): Long =
        (currentPositionMs - calculateRewindMs(pauseDurationMs)).coerceAtLeast(0L)
}
