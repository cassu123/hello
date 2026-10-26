package org.hello.reader.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import org.hello.reader.core.engine.audio.MediaLibraryTreeBuilder
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class MediaLibraryTreeBuilderTest {

    private val readaloudBook = Book(
        id = "b1",
        serverId = "s1",
        title = "Readaloud Novel",
        authors = listOf("Brandon Sanderson"),
        mediaType = BookMediaType.READALOUD,
    )

    private val audiobook = Book(
        id = "b2",
        serverId = "s1",
        title = "Audiobook Only",
        authors = listOf("Terry Pratchett", "Neil Gaiman"),
        mediaType = BookMediaType.AUDIO_ONLY,
    )

    private val ebookWithAudio = Book(
        id = "b3",
        serverId = "s1",
        title = "Hybrid Book",
        authors = listOf("Ursula K. Le Guin"),
        mediaType = BookMediaType.EBOOK_ONLY,
        hasAudiobook = true,
    )

    private val plainEbook = Book(
        id = "b4",
        serverId = "s1",
        title = "Text Only Book",
        authors = listOf("Frank Herbert"),
        mediaType = BookMediaType.EBOOK_ONLY,
    )

    @Test
    fun `buildRootItem constructs browsable root node`() {
        val root = MediaLibraryTreeBuilder.buildRootItem("Hello Reader")

        assertEquals(MediaLibraryTreeBuilder.ROOT_ID, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
        assertEquals(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, root.mediaMetadata.mediaType)
        assertEquals("Hello Reader", root.mediaMetadata.title.toString())
    }

    @Test
    fun `buildCategoryItem constructs browsable category nodes`() {
        val continueItem = MediaLibraryTreeBuilder.buildCategoryItem(
            MediaLibraryTreeBuilder.CATEGORY_CONTINUE_LISTENING,
            "Continue listening",
        )
        assertNotNull(continueItem)
        assertEquals(MediaLibraryTreeBuilder.CATEGORY_CONTINUE_LISTENING, continueItem!!.mediaId)
        assertEquals(true, continueItem.mediaMetadata.isBrowsable)
        assertEquals(false, continueItem.mediaMetadata.isPlayable)
        assertEquals("Continue listening", continueItem.mediaMetadata.title.toString())

        val downloadedItem = MediaLibraryTreeBuilder.buildCategoryItem(
            MediaLibraryTreeBuilder.CATEGORY_DOWNLOADED,
            "Downloaded",
        )
        assertNotNull(downloadedItem)
        assertEquals(MediaLibraryTreeBuilder.CATEGORY_DOWNLOADED, downloadedItem!!.mediaId)

        val recentItem = MediaLibraryTreeBuilder.buildCategoryItem(
            MediaLibraryTreeBuilder.CATEGORY_RECENT,
            "Recent",
        )
        assertNotNull(recentItem)
        assertEquals(MediaLibraryTreeBuilder.CATEGORY_RECENT, recentItem!!.mediaId)

        val invalid = MediaLibraryTreeBuilder.buildCategoryItem("unknown_category", "Unknown")
        assertNull(invalid)
    }

    @Test
    fun `isAudioEligible correctly identifies books with audio capabilities`() {
        assertTrue(MediaLibraryTreeBuilder.isAudioEligible(readaloudBook))
        assertTrue(MediaLibraryTreeBuilder.isAudioEligible(audiobook))
        assertTrue(MediaLibraryTreeBuilder.isAudioEligible(ebookWithAudio))
        assertFalse(MediaLibraryTreeBuilder.isAudioEligible(plainEbook))
    }

    @Test
    fun `filterAudioBooks filters out plain text ebooks`() {
        val allBooks = listOf(readaloudBook, audiobook, ebookWithAudio, plainEbook)
        val filtered = MediaLibraryTreeBuilder.filterAudioBooks(allBooks)

        assertEquals(3, filtered.size)
        assertEquals(listOf("b1", "b2", "b3"), filtered.map { it.id })
    }

    @Test
    fun `buildBookItem constructs playable media item with correct metadata`() {
        val item = MediaLibraryTreeBuilder.buildBookItem(readaloudBook)

        assertEquals("book:b1", item.mediaId)
        assertEquals("Readaloud Novel", item.mediaMetadata.title.toString())
        assertEquals("Brandon Sanderson", item.mediaMetadata.artist.toString())
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, item.mediaMetadata.mediaType)
    }

    @Test
    fun `buildBookItem formats multiple authors as comma-separated list`() {
        val item = MediaLibraryTreeBuilder.buildBookItem(audiobook)

        assertEquals("Terry Pratchett, Neil Gaiman", item.mediaMetadata.artist.toString())
    }
}
