package org.hello.reader.core.engine.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer manager (`ARCHITECTURE.md` Section 6.4) with linear volume fade-out
 * over the final [fadeDurationMs] (default 60s) and a reactive [remainingMs] flow
 * for the player UI.
 */
class SleepTimerManager(
    private val setPlayerVolume: (Float) -> Unit,
    private val onTimerExpired: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private var timerJob: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    fun startTimer(durationMs: Long, fadeDurationMs: Long = DEFAULT_FADE_DURATION_MS) {
        timerJob?.cancel()
        if (durationMs <= 0L) {
            _remainingMs.value = null
            setPlayerVolume(1.0f)
            onTimerExpired()
            return
        }
        _remainingMs.value = durationMs
        timerJob = scope.launch {
            val effectiveFadeMs = fadeDurationMs.coerceAtMost(durationMs)
            val normalDuration = (durationMs - effectiveFadeMs).coerceAtLeast(0L)

            val tickMs = 1_000L
            var elapsedNormal = 0L
            while (elapsedNormal < normalDuration) {
                val step = minOf(tickMs, normalDuration - elapsedNormal)
                delay(step)
                elapsedNormal += step
                _remainingMs.value = (durationMs - elapsedNormal).coerceAtLeast(0L)
            }

            if (effectiveFadeMs > 0L) {
                val steps = FADE_STEPS
                val stepDelay = (effectiveFadeMs / steps).coerceAtLeast(1L)
                for (i in steps downTo 0) {
                    val volume = i / steps.toFloat()
                    setPlayerVolume(volume)
                    _remainingMs.value = (i * stepDelay).coerceAtLeast(0L)
                    if (i > 0) delay(stepDelay)
                }
            }

            _remainingMs.value = null
            setPlayerVolume(1.0f)
            onTimerExpired()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _remainingMs.value = null
        setPlayerVolume(1.0f)
    }

    companion object {
        const val DEFAULT_FADE_DURATION_MS = 60_000L
        const val FADE_STEPS = 30
    }
}
