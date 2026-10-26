package org.hello.reader.core.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Dispatches physical hardware button presses (Volume Up/Down, Page Up/Down, DPAD)
 * to active reader canvases for page turning on e-readers (such as Onyx Boox Go Color 7)
 * and smartphones.
 */
object HardwareKeyDispatcher {

    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_VOLUME_UP = 24
    const val KEYCODE_VOLUME_DOWN = 25
    const val KEYCODE_PAGE_UP = 92
    const val KEYCODE_PAGE_DOWN = 93

    var isReaderActive: Boolean = false
    var volumeKeysTurnPages: Boolean = true
    var isAudioPlaying: Boolean = false
    var isAudioOnlyMode: Boolean = false
    var isOverlayOpen: Boolean = false
    var isChromeVisible: Boolean = false
    var isPagedMode: Boolean = true

    private val _pageTurnEvents = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val pageTurnEvents: SharedFlow<Int> = _pageTurnEvents.asSharedFlow()

    /**
     * Handles key down event. Returns true if consumed (e.g. to suppress system volume slider UI).
     */
    fun onKeyDown(keyCode: Int): Boolean {
        if (!isReaderActive) return false

        val isVolumeKey = keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN
        val isDpadKey = keyCode == KEYCODE_DPAD_UP || keyCode == KEYCODE_DPAD_DOWN ||
            keyCode == KEYCODE_DPAD_LEFT || keyCode == KEYCODE_DPAD_RIGHT
        val isPageKey = keyCode == KEYCODE_PAGE_UP || keyCode == KEYCODE_PAGE_DOWN

        // Volume keys:
        // Do not intercept volume keys if:
        // - Volume keys page turn setting is disabled
        // - Audio is actively playing or in audio-only view/listening mode (allow standard system volume adjustment)
        // - Sheets/dialogs/overlays are open
        // - Not in paged layout mode (e.g. continuous scroll)
        if (isVolumeKey) {
            if (!volumeKeysTurnPages || isAudioPlaying || isAudioOnlyMode || isOverlayOpen || !isPagedMode) {
                return false
            }
        }

        // DPAD keys:
        // Do not intercept DPAD keys when reader chrome is visible or dialogs/overlays are open,
        // so switch access, directional keyboard navigation, and accessibility can focus controls.
        // Also do not intercept DPAD keys if not in paged mode.
        if (isDpadKey) {
            if (isChromeVisible || isOverlayOpen || !isPagedMode) {
                return false
            }
        }

        // Dedicated page keys (PAGE_UP / PAGE_DOWN):
        // Only ignore if an overlay/dialog is actively capturing input or not in paged mode.
        if (isPageKey) {
            if (isOverlayOpen || !isPagedMode) {
                return false
            }
        }

        return when (keyCode) {
            KEYCODE_VOLUME_DOWN,
            KEYCODE_PAGE_DOWN,
            KEYCODE_DPAD_RIGHT,
            KEYCODE_DPAD_DOWN -> {
                _pageTurnEvents.tryEmit(1)
                true
            }
            KEYCODE_VOLUME_UP,
            KEYCODE_PAGE_UP,
            KEYCODE_DPAD_LEFT,
            KEYCODE_DPAD_UP -> {
                _pageTurnEvents.tryEmit(-1)
                true
            }
            else -> false
        }
    }
}
