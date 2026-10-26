package org.hello.reader.core.engine

import org.hello.reader.core.engine.audio.SleepTimerManager
import org.hello.reader.core.engine.audio.SmartRewindPolicy
import org.hello.reader.core.engine.reader.ReaderMode
import org.hello.reader.core.engine.reader.ReaderStateMachine
import org.hello.reader.core.engine.reader.SyncedPlaybackState
import org.hello.reader.core.engine.smil.EpubOverlayExtractor
import org.hello.reader.core.engine.smil.SmilParser
import org.hello.reader.core.engine.smil.SmilSyncEngine
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.ReaderThemePreset
import org.hello.reader.core.model.WcagContrastValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SmilAndAudioEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleSmilXml = """
        <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
          <body>
            <seq id="seq1">
              <par id="par_1">
                <text src="xhtml/chapter1.xhtml#sentence_1"/>
                <audio src="audio/chapter1.mp3" clipBegin="00:00:02.150" clipEnd="00:00:05.800"/>
              </par>
              <par id="par_2">
                <text src="xhtml/chapter1.xhtml#sentence_2"/>
                <audio src="audio/chapter1.mp3" clipBegin="00:00:05.800" clipEnd="00:00:10.200"/>
              </par>
              <par id="par_3">
                <text src="xhtml/chapter2.xhtml#sentence_1"/>
                <audio src="audio/chapter1.mp3" clipBegin="00:00:10.200" clipEnd="15.5s"/>
              </par>
            </seq>
          </body>
        </smil>
    """.trimIndent()

    @Test
    fun `SmilParser parses SMIL 3 par nodes and clock values accurately`() {
        val nodes = SmilParser.parse(sampleSmilXml)
        assertEquals(3, nodes.size)
        assertEquals("sentence_1", nodes[0].fragmentId)
        assertEquals(2_150L, nodes[0].clipBeginMs)
        assertEquals(5_800L, nodes[0].clipEndMs)
        assertEquals(15_500L, nodes[2].clipEndMs)

        assertEquals(3_723_450L, SmilParser.parseClockValueMs("01:02:03.450"))
        assertEquals(250L, SmilParser.parseClockValueMs("250ms"))
        assertEquals(90_000L, SmilParser.parseClockValueMs("1.5min"))
    }

    @Test
    fun `SmilSyncEngine binary searches timestamp and resolves tap-to-speak`() {
        val engine = SmilSyncEngine(SmilParser.parse(sampleSmilXml))

        assertNull(engine.onPlaybackPositionChanged(1_000L))
        val h1 = engine.onPlaybackPositionChanged(3_500L)
        assertNotNull(h1)
        assertEquals("sentence_1", h1!!.elementId)

        val h2 = engine.onPlaybackPositionChanged(7_000L)
        assertEquals("sentence_2", h2?.elementId)

        val tapNode = engine.lookupTimeRange("sentence_2", "xhtml/chapter1.xhtml")
        assertEquals(5_800L, tapNode?.clipBeginMs)
    }

    @Test
    fun `SmartRewindPolicy calculates expected rewind offsets`() {
        assertEquals(0L, SmartRewindPolicy.calculateRewindMs(3_000L))
        assertEquals(2_000L, SmartRewindPolicy.calculateRewindMs(15_000L))
        assertEquals(10_000L, SmartRewindPolicy.calculateRewindMs(5 * 60_000L))
        assertEquals(30_000L, SmartRewindPolicy.calculateRewindMs(45 * 60_000L))
        assertEquals(0L, SmartRewindPolicy.applySmartRewind(5_000L, 45 * 60_000L))
    }

    @Test
    fun `SleepTimerManager fades volume linearly and invokes expiry callback`() = runTest {
        val volumes = mutableListOf<Float>()
        var expired = false
        val timer = SleepTimerManager(
            setPlayerVolume = { volumes += it },
            onTimerExpired = { expired = true },
            scope = backgroundScope,
        )

        timer.startTimer(durationMs = 10_000L, fadeDurationMs = 3_000L)
        advanceTimeBy(11_000L)

        assertTrue(expired)
        assertTrue(volumes.size >= 30)
        assertEquals(1.0f, volumes.last())
    }

    @Test
    fun `ReaderStateMachine switches seamlessly between ReadingOnly and ReadaloudSynced`() {
        var now = 1_000L
        val sm = ReaderStateMachine(clock = { now })
        val book = Book(
            id = "b1",
            serverId = "s1",
            title = "Synced Book",
            authors = listOf("River Song"),
            mediaType = BookMediaType.READALOUD,
            durationSeconds = 100L,
        )
        sm.openBook(book, smilNodes = SmilParser.parse(sampleSmilXml), preferAudioOnOpen = false)
        assertTrue(sm.state.value is ReaderMode.ReadingOnly)

        // Tap-to-Speak transitions directly into ReadaloudSynced at sentence_2 (5_800ms)
        val node = sm.onSentenceTapped("sentence_2", "xhtml/chapter1.xhtml")
        assertEquals(5_800L, node?.clipBeginMs)
        val synced = sm.state.value as ReaderMode.ReadaloudSynced
        assertEquals(SyncedPlaybackState.PLAYING, synced.playbackState)
        assertEquals(5_800L, synced.audioPositionMs)
        assertEquals("sentence_2", synced.highlight?.elementId)

        // Pause for 20s and resume -> Smart rewind subtracts 2_000ms (to 3_800ms -> sentence_1)
        sm.onPause()
        now += 20_000L
        sm.onPlay()
        val resumed = sm.state.value as ReaderMode.ReadaloudSynced
        assertEquals(3_800L, resumed.audioPositionMs)
        assertEquals("sentence_1", resumed.highlight?.elementId)

        // Speed snapping in 0.05 increments
        sm.setPlaybackSpeed(1.76f)
        assertEquals(1.75f, (sm.state.value as ReaderMode.ReadaloudSynced).playbackSpeed)
    }

    @Test
    fun `Whispersync two-way SMIL mapping between active sentence and audio position`() {
        val sm = ReaderStateMachine(clock = { 5000L })
        val book = Book(
            id = "b1",
            serverId = "s1",
            title = "Synced Book",
            authors = listOf("River Song"),
            mediaType = BookMediaType.READALOUD,
            durationSeconds = 100L,
        )
        val smilNodes = SmilParser.parse(sampleSmilXml)

        // 1. User arrives from a phone where they were listening at 2_150ms (sentence_1)
        // Opening in silent ReadingOnly maps audio position (2_150ms) to activeSentenceId ("sentence_1")
        val audioCheckpoint = org.hello.reader.core.model.SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = null,
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 2_150L,
            totalDurationMs = 100_000L,
            progressPercent = 2.15f,
            activeSentenceId = null,
        )
        sm.openBook(book, checkpoint = audioCheckpoint, smilNodes = smilNodes, preferAudioOnOpen = false)
        val readingMode = sm.state.value as ReaderMode.ReadingOnly
        assertEquals("sentence_1", readingMode.activeSentenceId)

        // 2. User reads silently down to sentence_2, then checkpoint is saved
        sm.onReadingPositionChanged("sentence_2", 5.8f)
        val savedFromReading = sm.toSyncCheckpoint("s1", "u1")!!
        assertEquals("sentence_2", savedFromReading.activeSentenceId)
        // Saved checkpoint resolves audioPositionMs for sentence_2 (5_800ms) from SMIL
        assertEquals(5_800L, savedFromReading.audioPositionMs)

        // 3. User taps Play / enableSynchronizedNarration() -> starts audio at sentence_2 (5_800ms)
        sm.enableSynchronizedNarration()
        val syncedMode = sm.state.value as ReaderMode.ReadaloudSynced
        assertEquals(SyncedPlaybackState.PLAYING, syncedMode.playbackState)
        assertEquals(5_800L, syncedMode.audioPositionMs)
        assertEquals("sentence_2", syncedMode.highlight?.elementId)

        // 4. User pauses -> switches back to ReadingOnly at sentence_2
        sm.disableSynchronizedNarration()
        val pausedReadingMode = sm.state.value as ReaderMode.ReadingOnly
        assertEquals("sentence_2", pausedReadingMode.activeSentenceId)
    }

    @Test
    fun `WcagContrastValidator validates preset and low-contrast themes`() {
        ReaderThemePreset.entries.forEach { preset ->
            val report = WcagContrastValidator.evaluate(preset.backgroundHex, preset.foregroundHex)
            assertNotNull(report)
            assertTrue("Preset ${preset.name} must pass WCAG AA", report!!.passesAA)
        }

        val bad = WcagContrastValidator.evaluate("#FFFFFF", "#EEEEEE")
        assertNotNull(bad)
        assertFalse(bad!!.passesAA)
    }

    @Test
    fun `EpubOverlayExtractor extracts SMIL nodes, XHTML fragment sentences, and embedded audio`() {
        val epubFile = File(tempFolder.root, "readaloud.epub")
        ZipOutputStream(epubFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("OEBPS/MediaOverlays/chapter1.smil"))
            zip.write(
                """
                <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
                  <body>
                    <par id="p1">
                      <text src="../Text/chapter1.xhtml#s1"/>
                      <audio src="../Audio/chapter1.mp3" clipBegin="0s" clipEnd="4.2s"/>
                    </par>
                    <par id="p2">
                      <text src="../Text/chapter1.xhtml#s2"/>
                      <audio src="../Audio/chapter1.mp3" clipBegin="4.2s" clipEnd="9.0s"/>
                    </par>
                  </body>
                </smil>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/Text/chapter1.xhtml"))
            zip.write(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body>
                    <p><span id="s1">Hello, Sweetie &amp; welcome to the Library.</span> <span id="s2">Spoilers ahead!</span></p>
                  </body>
                </html>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/Audio/chapter1.mp3"))
            zip.write(byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00))
            zip.closeEntry()
        }

        val audioOutDir = File(tempFolder.root, "audio_cache")
        val extracted = EpubOverlayExtractor.extract(epubFile, audioOutDir)
        assertNotNull(extracted)
        assertEquals(2, extracted!!.smilNodes.size)
        assertEquals("OEBPS/Text/chapter1.xhtml", extracted.smilNodes[0].textHref)
        assertEquals("OEBPS/Audio/chapter1.mp3", extracted.smilNodes[0].audioSrc)
        assertEquals("Hello, Sweetie & welcome to the Library.", extracted.sentences[0].text)
        assertEquals("Spoilers ahead!", extracted.sentences[1].text)
        assertNotNull(extracted.extractedAudioFile)
        assertTrue(extracted.extractedAudioFile!!.isFile)
        assertEquals(5L, extracted.extractedAudioFile!!.length())
    }

    @Test
    fun `EpubOverlayExtractor extracts XHTML paragraphs for EBOOK_ONLY epub without SMIL`() {
        val epubFile = File(tempFolder.root, "ebook_only.epub")
        ZipOutputStream(epubFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("OPS/chapter1.xhtml"))
            zip.write(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <body>
                    <h1>Chapter One</h1>
                    <p>First paragraph of the standalone e-book.</p>
                    <p>Second paragraph with <em>italic</em> emphasis.</p>
                  </body>
                </html>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }

        val extracted = EpubOverlayExtractor.extract(epubFile)
        assertNotNull(extracted)
        assertEquals(3, extracted!!.sentences.size)
        assertEquals("Chapter One", extracted.sentences[0].text)
        assertEquals("First paragraph of the standalone e-book.", extracted.sentences[1].text)
        assertEquals("Second paragraph with italic emphasis.", extracted.sentences[2].text)
        assertNull(extracted.extractedAudioFile)
    }

    @Test
    fun `parseIdFragments recursively extracts nested spans and inline elements without placeholders`() {
        val storytellerXhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <div id="c002">
                  <p id="p1">
                    <span id="c002-s78">Sentence with <em id="em1">emphasis</em> and inline markup.</span>
                    <span id="c002-s79">Sentence <ruby id="rb1">283<rt id="rt1">kanji</rt></ruby> (c002-s79) text.</span>
                  </p>
                  <p id="p2">
                    <span xml:id="c002-s80">Sentence using xml:id attribute.</span>
                  </p>
                </div>
              </body>
            </html>
        """.trimIndent()

        val fragments = EpubOverlayExtractor.parseIdFragments(storytellerXhtml)
        assertTrue(fragments.containsKey("c002"))
        assertTrue(fragments.containsKey("p1"))
        assertTrue(fragments.containsKey("c002-s78"))
        assertEquals("Sentence with emphasis and inline markup.", fragments["c002-s78"])
        assertTrue(fragments.containsKey("em1"))
        assertEquals("emphasis", fragments["em1"])
        assertTrue(fragments.containsKey("c002-s79"))
        assertEquals("Sentence 283kanji (c002-s79) text.", fragments["c002-s79"])
        assertTrue(fragments.containsKey("c002-s80"))
        assertEquals("Sentence using xml:id attribute.", fragments["c002-s80"])
    }
}
