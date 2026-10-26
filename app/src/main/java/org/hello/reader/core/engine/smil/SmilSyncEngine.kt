package org.hello.reader.core.engine.smil

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HighlightEvent(
    val textHref: String,
    val elementId: String,
    val audioSrc: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
)

/**
 * Synchronizes Media3 ExoPlayer playback positions with Readium EPUB 3 DOM element IDs
 * using binary search over sorted [SmilParNode] intervals.
 *
 * - O(log N) lookup by audio timestamp (`onPlaybackPositionChanged`) for zero-drift highlighting.
 * - O(1) lookup by DOM element ID (`lookupTimeRange`) for instant Tap-to-Speak seeking.
 */
class SmilSyncEngine(nodes: List<SmilParNode> = emptyList()) {

    private var sortedNodes: List<SmilParNode> = emptyList()
    private var byAudioSrc: Map<String, List<SmilParNode>> = emptyMap()
    private var byElementKey: Map<String, SmilParNode> = emptyMap()
    private var byFragmentId: Map<String, SmilParNode> = emptyMap()

    private val _activeHighlight = MutableStateFlow<HighlightEvent?>(null)
    val activeHighlight: StateFlow<HighlightEvent?> = _activeHighlight.asStateFlow()

    init {
        loadNodes(nodes)
    }

    fun loadNodes(nodes: List<SmilParNode>) {
        sortedNodes = nodes.sortedBy { it.clipBeginMs }
        byAudioSrc = sortedNodes.groupBy { normalizePath(it.audioSrc) }
        byElementKey = sortedNodes.associateBy { "${normalizePath(it.textHref)}#${it.fragmentId}" }
        byFragmentId = sortedNodes.associateBy { it.fragmentId }
        _activeHighlight.value = null
    }

    /** Maximum SMIL clipEndMs across all nodes, useful as fallback total audio duration. */
    fun maxClipEndMs(): Long? = sortedNodes.maxOfOrNull { it.clipEndMs }

    /**
     * Binary-searches the active [SmilParNode] at [positionMs] (optionally scoped to [audioSrc]).
     */
    fun lookupSmilNode(positionMs: Long, audioSrc: String? = null): SmilParNode? {
        val candidates = if (audioSrc != null) {
            byAudioSrc[normalizePath(audioSrc)] ?: return null
        } else {
            sortedNodes
        }
        if (candidates.isEmpty()) return null

        var low = 0
        var high = candidates.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val node = candidates[mid]
            when {
                positionMs < node.clipBeginMs -> high = mid - 1
                positionMs >= node.clipEndMs -> low = mid + 1
                else -> return node
            }
        }
        return null
    }

    /**
     * Advances the sync engine to [positionMs] and emits a new [HighlightEvent] only when
     * the active sentence changes.
     */
    fun onPlaybackPositionChanged(positionMs: Long, audioSrc: String? = null): HighlightEvent? {
        val node = lookupSmilNode(positionMs, audioSrc)
        val event = node?.toHighlightEvent()
        if (_activeHighlight.value != event) {
            _activeHighlight.value = event
        }
        return event
    }

    /**
     * Tap-to-Speak: Resolves a clicked paragraph/sentence DOM [elementId] (and optional
     * chapter [textHref]) to its [SmilParNode] containing `audioSrc` and `clipBeginMs`.
     */
    fun lookupTimeRange(elementId: String, textHref: String? = null): SmilParNode? {
        if (textHref != null) {
            byElementKey["${normalizePath(textHref)}#$elementId"]?.let { return it }
        }
        return byFragmentId[elementId]
    }

    private fun SmilParNode.toHighlightEvent(): HighlightEvent = HighlightEvent(
        textHref = textHref,
        elementId = fragmentId,
        audioSrc = audioSrc,
        clipBeginMs = clipBeginMs,
        clipEndMs = clipEndMs,
    )

    private fun normalizePath(path: String): String =
        path.trim().trimStart('/').replace('\\', '/')
}
