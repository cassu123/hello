package org.hello.reader.core.engine

import android.media.MediaMetadataRetriever
import java.io.File
import org.hello.reader.core.engine.smil.AudioTrackItem
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.hello.reader.core.engine.smil.EpubOverlayExtractor
import org.hello.reader.core.engine.smil.SmilParNode
import org.hello.reader.core.engine.smil.SmilSyncEngine
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.util.NaturalFileOrder
import org.hello.reader.feature.reader.ReaderSentenceItem

data class LoadedBookContent(
    val smilNodes: List<SmilParNode>,
    val sentences: List<ReaderSentenceItem>,
    val audioFile: File?,
    val audioTracks: List<AudioTrackItem> = emptyList(),
    val tocTitles: Map<String, String> = emptyMap(),
    val chapters: BookChapters = BookChapters.EMPTY,
)

object BookContentLoader {
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav")

    fun resolveAudioTracks(path: String?): List<File> {
        if (path.isNullOrBlank()) return emptyList()
        val target = File(path)
        if (!target.exists()) return emptyList()
        return if (target.isDirectory) {
            target.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                .sortedWith(NaturalFileOrder)
                .toList()
        } else if (target.isFile && target.extension.lowercase() in AUDIO_EXTENSIONS) {
            listOf(target)
        } else {
            emptyList()
        }
    }

    fun buildAudioTrackItems(files: List<File>, baseDir: File?, totalDurationSeconds: Long): List<AudioTrackItem> {
        if (files.isEmpty()) return emptyList()
        var currentOffsetMs = 0L
        val items = mutableListOf<AudioTrackItem>()
        for (file in files) {
            val durationMs = getAudioDurationMs(file)
            val relPath = if (baseDir != null) {
                file.relativeToOrNull(baseDir)?.path?.replace('\\', '/') ?: file.name
            } else {
                file.name
            }
            items += AudioTrackItem(
                file = file,
                audioSrc = relPath,
                startOffsetMs = currentOffsetMs,
                durationMs = durationMs,
            )
            currentOffsetMs += durationMs
        }
        if (items.size == 1 && items[0].durationMs == 0L && totalDurationSeconds > 0) {
            return listOf(items[0].copy(durationMs = totalDurationSeconds * 1_000L))
        }
        return items
    }

    fun getAudioDurationMs(file: File): Long {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            timeString?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
    }

    fun loadBookContent(
        book: Book,
        textPath: String? = book.localFilePath,
        audioPath: String? = book.localAudioPath,
    ): LoadedBookContent? {
        val standaloneTracks = resolveAudioTracks(audioPath)
        val standaloneAudio = standaloneTracks.firstOrNull()

        val epubFile = textPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile && it.name.endsWith(".epub", ignoreCase = true) }

        if (epubFile != null) {
            val extracted = EpubOverlayExtractor.extract(
                epubFile = epubFile,
                audioOutputDir = if (standaloneAudio == null) epubFile.parentFile else null,
            )
            if (extracted != null && extracted.sentences.isNotEmpty()) {
                val items = extracted.sentences.map { seg ->
                    ReaderSentenceItem(
                        elementId = seg.elementId,
                        textHref = seg.textHref,
                        text = seg.text,
                        clipBeginMs = seg.clipBeginMs,
                        clipEndMs = seg.clipEndMs,
                        isChapterStart = seg.isChapterStart,
                        chapterTitle = seg.chapterTitle,
                        rawHtmlId = seg.rawHtmlId,
                    )
                }
                val primaryAudio = standaloneAudio ?: extracted.extractedAudioFile
                val audioDir = audioPath?.let(::File)?.takeIf { it.isDirectory }
                val tracks = if (extracted.audioTracks.isNotEmpty()) {
                    extracted.audioTracks
                } else if (standaloneTracks.isNotEmpty()) {
                    buildAudioTrackItems(standaloneTracks, audioDir, book.durationSeconds)
                } else if (primaryAudio != null) {
                    val durationMs = if (book.durationSeconds > 0) book.durationSeconds * 1_000L else getAudioDurationMs(primaryAudio)
                    listOf(AudioTrackItem(primaryAudio, primaryAudio.name, 0L, durationMs))
                } else {
                    emptyList()
                }
                val chapters = BookChapters.build(
                    paragraphs = items.map {
                        ChapterParagraph(it.elementId, it.textHref, it.text, it.clipBeginMs, it.clipEndMs)
                    },
                    tocTitles = extracted.tocTitles,
                    timed = (primaryAudio != null || tracks.isNotEmpty()) && book.mediaType != BookMediaType.EBOOK_ONLY,
                )
                return LoadedBookContent(
                    smilNodes = extracted.smilNodes,
                    sentences = items,
                    audioFile = primaryAudio,
                    audioTracks = tracks,
                    tocTitles = extracted.tocTitles,
                    chapters = chapters,
                )
            }
        } else if (standaloneTracks.isNotEmpty() && (book.mediaType == BookMediaType.AUDIO_ONLY || book.hasAudiobook)) {
            val audioDir = audioPath?.let(::File)?.takeIf { it.isDirectory }
            val tracks = buildAudioTrackItems(standaloneTracks, audioDir, book.durationSeconds)
            val chapters = BookChapters.buildFromTracks(tracks)
            return LoadedBookContent(
                smilNodes = emptyList(),
                sentences = emptyList(),
                audioFile = standaloneTracks.first(),
                audioTracks = tracks,
                tocTitles = emptyMap(),
                chapters = chapters,
            )
        }

        return null
    }

    /**
     * Resolves the effective audio playback position in milliseconds from a [SyncCheckpoint].
     * Handles positions lacking an audio clock (such as Storyteller v2 Readium positions with audioPositionMs == 0)
     * by resolving [SyncCheckpoint.activeSentenceId] and [SyncCheckpoint.readingCfi] against SMIL overlay nodes.
     */
    fun resolveCheckpointAudioPosition(
        checkpoint: SyncCheckpoint?,
        content: LoadedBookContent,
    ): Long {
        if (checkpoint == null) return 0L
        val rawPosMs = checkpoint.audioPositionMs
        if (rawPosMs > 0L) return rawPosMs

        val sentenceId = checkpoint.activeSentenceId
        val cfi = checkpoint.readingCfi

        if (sentenceId != null && sentenceId.startsWith("t=")) {
            val trackSec = sentenceId.removePrefix("t=").toDoubleOrNull() ?: 0.0
            val track = if (cfi == null) null else {
                content.audioTracks.firstOrNull { it.audioSrc == cfi || it.file.path == cfi }
                    ?: content.audioTracks.firstOrNull { it.audioSrc.endsWith("/$cfi") || cfi.endsWith("/" + it.audioSrc) }
                    ?: content.audioTracks.filter { it.file.name == cfi || it.file.name == cfi.substringAfterLast('/') }.singleOrNull()
            }
            return if (track != null) track.startOffsetMs + (trackSec * 1000.0).toLong() else 0L
        }

        if (sentenceId != null && content.smilNodes.isNotEmpty()) {
            val smilEngine = SmilSyncEngine(content.smilNodes)
            val directRange = smilEngine.lookupTimeRange(sentenceId, cfi)
            if (directRange != null) return directRange.clipBeginMs

            val matchedSentence = content.sentences.firstOrNull {
                it.elementId == sentenceId || (it.rawHtmlId != null && it.rawHtmlId == sentenceId)
            }
            if (matchedSentence != null) {
                val sentenceRange = smilEngine.lookupTimeRange(matchedSentence.elementId, matchedSentence.textHref)
                if (sentenceRange != null) return sentenceRange.clipBeginMs
                if (matchedSentence.clipBeginMs > 0L) return matchedSentence.clipBeginMs
            }
        }

        val totalMs = checkpoint.totalDurationMs.takeIf { it > 0L }
            ?: content.smilNodes.maxOfOrNull { it.clipEndMs }?.takeIf { it > 0L }
            ?: 0L
        if (checkpoint.progressPercent > 0f && totalMs > 0L) {
            return ((checkpoint.progressPercent / 100f) * totalMs).toLong()
        }
        return 0L
    }
}
