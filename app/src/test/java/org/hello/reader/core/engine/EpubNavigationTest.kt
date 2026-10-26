package org.hello.reader.core.engine

import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.reader.ReaderStateMachine
import org.hello.reader.core.engine.smil.EpubOverlayExtractor
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Spine order and table-of-contents titles from the OPF, EPUB 3 nav and EPUB 2 NCX. */
class EpubNavigationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun epub(vararg entries: Pair<String, String>): File {
        val file = tmp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private val container = "META-INF/container.xml" to """
        <container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
    """

    private fun chapter(heading: String, body: String) = """
        <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>$heading</h1><p>$body</p></body></html>
    """

    @Test
    fun `e-book paragraphs follow the spine, not file names, and titles come from the nav document`() {
        val file = epub(
            container,
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="c2" href="Text/chapter%202.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c10" href="Text/chapter10.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="c2"/><itemref idref="c10"/></spine></package>
            """,
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="landmarks"><ol><li><a href="Text/chapter10.xhtml">Wrong list</a></li></ol></nav>
                  <nav epub:type="toc"><ol>
                    <li><a href="Text/chapter%202.xhtml#start">Second <em>Chapter</em></a></li>
                    <li><a href="Text/chapter10.xhtml">Tenth Chapter</a></li>
                  </ol></nav>
                </body></html>
            """,
            "OEBPS/Text/chapter10.xhtml" to chapter("Ten", "Later text."),
            "OEBPS/Text/chapter 2.xhtml" to chapter("Two", "Earlier text."),
        )

        val extracted = EpubOverlayExtractor.extract(file)!!
        assertEquals(listOf("Two", "Earlier text.", "Ten", "Later text."), extracted.sentences.map { it.text })
        assertEquals(
            mapOf("OEBPS/Text/chapter 2.xhtml" to "Second Chapter", "OEBPS/Text/chapter10.xhtml" to "Tenth Chapter"),
            extracted.tocTitles,
        )
    }

    @Test
    fun `EPUB 2 books take their titles from the NCX`() {
        val file = epub(
            container,
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine toc="ncx"><itemref idref="a"/></spine></package>
            """,
            "OEBPS/toc.ncx" to """
                <ncx><navMap>
                  <navPoint id="p1" playOrder="1">
                    <navLabel><text>Opening</text></navLabel>
                    <content src="a.xhtml#top"/>
                  </navPoint>
                </navMap></ncx>
            """,
            "OEBPS/a.xhtml" to chapter("A", "Body."),
        )

        val extracted = EpubOverlayExtractor.extract(file)!!
        assertEquals(mapOf("OEBPS/a.xhtml" to "Opening"), extracted.tocTitles)
    }

    @Test
    fun `single xhtml file with multiple chapters recognizes chapter starts and titles`() {
        val file = epub(
            container,
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="c1" href="Text/book.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="c1"/></spine></package>
            """,
            "OEBPS/nav.xhtml" to """
                <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="toc"><ol>
                    <li><a href="Text/book.xhtml#ch1">Chapter One</a></li>
                    <li><a href="Text/book.xhtml#ch2">Chapter Two</a></li>
                  </ol></nav>
                </body></html>
            """,
            "OEBPS/Text/book.xhtml" to """
                <html><body>
                  <h1 id="ch1">Chapter One</h1>
                  <p>First paragraph of chapter one.</p>
                  <h1 id="ch2">Chapter Two</h1>
                  <p>First paragraph of chapter two.</p>
                </body></html>
            """,
        )

        val extracted = EpubOverlayExtractor.extract(file)!!
        assertEquals(listOf("p_1", "p_2", "p_3", "p_4"), extracted.sentences.map { it.elementId })
        val chapterStarts = extracted.sentences.filter { it.isChapterStart }
        assertEquals(2, chapterStarts.size)
        assertEquals("Chapter One", chapterStarts[0].chapterTitle)
        assertEquals("Chapter Two", chapterStarts[1].chapterTitle)
    }

    @Test
    fun `duplicate html ids across files still produce unique sequential paragraph ids`() {
        val file = epub(
            container,
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>
            """,
            "OEBPS/Text/ch1.xhtml" to """
                <html><body>
                  <p id="p1">First paragraph in file 1.</p>
                  <p id="p2">Second paragraph in file 1.</p>
                </body></html>
            """,
            "OEBPS/Text/ch2.xhtml" to """
                <html><body>
                  <p id="p1">First paragraph in file 2, repeating id p1.</p>
                  <p id="p2">Second paragraph in file 2, repeating id p2.</p>
                </body></html>
            """,
        )

        val extracted = EpubOverlayExtractor.extract(file)!!
        val ids = extracted.sentences.map { it.elementId }
        assertEquals(listOf("p_1", "p_2", "p_3", "p_4"), ids)
        assertEquals(4, ids.toSet().size)
    }

    @Test
    fun `reading position and chapter changes are recorded in reading mode`() {
        val machine = ReaderStateMachine(clock = { 0L })
        machine.openBook(
            Book(id = "b", serverId = "s", title = "T", authors = emptyList(), mediaType = BookMediaType.EBOOK_ONLY),
        )
        machine.onReadingPositionChanged("p_7", 42f)
        machine.onChapterChanged(3, "Four")

        val mode = machine.state.value as ReaderMode.ReadingOnly
        assertEquals("p_7", mode.activeSentenceId)
        assertEquals(42f, mode.progressPercent)
        assertEquals(3, mode.chapterIndex)
        assertEquals("Four", mode.chapterTitle)
        assertEquals("p_7", machine.toSyncCheckpoint("s", "u")!!.activeSentenceId)
    }

    @Test
    fun `multi-file readalouds extract all audio tracks and map per-chapter relative clip timings to a continuous timeline`() {
        val audioDir = tmp.newFolder("audio-out")
        val file = epub(
            container,
            "OEBPS/content.opf" to """
                <package><manifest>
                  <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml" media-overlay="s1"/>
                  <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml" media-overlay="s2"/>
                  <item id="s1" href="Smil/ch1.smil" media-type="application/smil+xml"/>
                  <item id="s2" href="Smil/ch2.smil" media-type="application/smil+xml"/>
                  <item id="a1" href="Audio/ch1.mp3" media-type="audio/mpeg"/>
                  <item id="a2" href="Audio/ch2.mp3" media-type="audio/mpeg"/>
                </manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>
            """,
            "OEBPS/Smil/ch1.smil" to """
                <smil><body>
                  <par><text src="../Text/ch1.xhtml#p1"/><audio src="../Audio/ch1.mp3" clipBegin="0s" clipEnd="10s"/></par>
                  <par><text src="../Text/ch1.xhtml#p2"/><audio src="../Audio/ch1.mp3" clipBegin="10s" clipEnd="25s"/></par>
                </body></smil>
            """,
            "OEBPS/Smil/ch2.smil" to """
                <smil><body>
                  <par><text src="../Text/ch2.xhtml#p3"/><audio src="../Audio/ch2.mp3" clipBegin="0s" clipEnd="15s"/></par>
                  <par><text src="../Text/ch2.xhtml#p4"/><audio src="../Audio/ch2.mp3" clipBegin="15s" clipEnd="30s"/></par>
                </body></smil>
            """,
            "OEBPS/Text/ch1.xhtml" to """<html><body><p id="p1">Chapter 1 start.</p><p id="p2">Chapter 1 end.</p></body></html>""",
            "OEBPS/Text/ch2.xhtml" to """<html><body><p id="p3">Chapter 2 start.</p><p id="p4">Chapter 2 end.</p></body></html>""",
            "OEBPS/Audio/ch1.mp3" to "AUDIO_CH1_DATA",
            "OEBPS/Audio/ch2.mp3" to "AUDIO_CH2_DATA",
        )

        val extracted = EpubOverlayExtractor.extract(file, audioOutputDir = audioDir)!!
        assertEquals(2, extracted.audioTracks.size)
        assertEquals(0L, extracted.audioTracks[0].startOffsetMs)
        assertEquals(25_000L, extracted.audioTracks[0].durationMs)
        assertEquals(25_000L, extracted.audioTracks[1].startOffsetMs)
        assertEquals(30_000L, extracted.audioTracks[1].durationMs)

        // Chapter 1 nodes are 0..10s and 10..25s
        assertEquals(0L, extracted.smilNodes[0].clipBeginMs)
        assertEquals(10_000L, extracted.smilNodes[0].clipEndMs)
        assertEquals(10_000L, extracted.smilNodes[1].clipBeginMs)
        assertEquals(25_000L, extracted.smilNodes[1].clipEndMs)

        // Chapter 2 nodes restart at 0 in their file, but are mapped to 25s..40s and 40s..55s!
        assertEquals(25_000L, extracted.smilNodes[2].clipBeginMs)
        assertEquals(40_000L, extracted.smilNodes[2].clipEndMs)
        assertEquals(40_000L, extracted.smilNodes[3].clipBeginMs)
        assertEquals(55_000L, extracted.smilNodes[3].clipEndMs)

        // Sentences also reflect the continuous timeline
        assertEquals(25_000L, extracted.sentences[2].clipBeginMs)
        assertEquals(40_000L, extracted.sentences[2].clipEndMs)
    }
}
