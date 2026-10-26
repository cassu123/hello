package org.hello.reader.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.hello.reader.core.model.TwoPageViewMode
import org.hello.reader.feature.reader.shouldDisplayDualPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceProfileAndKeyDispatcherTest {

    @Before
    fun setup() {
        HardwareKeyDispatcher.isReaderActive = false
        HardwareKeyDispatcher.volumeKeysTurnPages = true
        HardwareKeyDispatcher.isAudioPlaying = false
        HardwareKeyDispatcher.isAudioOnlyMode = false
        HardwareKeyDispatcher.isOverlayOpen = false
        HardwareKeyDispatcher.isChromeVisible = false
        HardwareKeyDispatcher.isPagedMode = true
    }

    @Test
    fun `isEInkDevice detects Onyx Boox Color 7 and other e-readers`() {
        // Onyx Boox Go Color 7
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Onyx", model = "Go Color 7"))
        assertTrue(DeviceProfile.isEInkDevice(brand = "Onyx", model = "Boox Page"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Onyx", model = "Palma"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Onyx", device = "BooxTabUltra"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Kobo", model = "Clara"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "PocketBook", model = "Era"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Supernote", model = "A6X2"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Tolino", model = "Vision 6"))

        // Hisense e-ink series (A5, A7, A9, HLTE)
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "A5"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "Hisense A5 Pro"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "A7 CC"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "A9"))
        assertTrue(DeviceProfile.isEInkDevice(manufacturer = "Hisense", device = "HLTE202N"))

        // Standard smartphones and tablets are NOT classified as e-ink
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Google", model = "Pixel 9 Pro"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Google", model = "Pixel Tablet"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Google", model = "Pixel 9 Pro Fold"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Google", model = "Pixel 11 Pro Fold"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "onn", model = "100071481"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Samsung", model = "SM-G998B"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Samsung", model = "SM-A750F"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Samsung", model = "Galaxy S24"))

        // Regular non-e-ink Hisense phones (including models containing "a5" inside a longer token) are not classified as e-ink
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "Infinity H60"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "Rock 5"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "Hisense-XBA500"))
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Hisense", model = "HS-A510"))

        // Loose "Color 7" on a generic device is NOT classified as e-ink
        assertFalse(DeviceProfile.isEInkDevice(manufacturer = "Generic", model = "Tablet Color 7"))
    }

    @Test
    fun `isOnyxDevice detects Boox devices accurately`() {
        assertTrue(DeviceProfile.isOnyxDevice(manufacturer = "Onyx", model = "Go Color 7"))
        assertTrue(DeviceProfile.isOnyxDevice(brand = "Onyx", model = "Boox Page"))
        assertTrue(DeviceProfile.isOnyxDevice(manufacturer = "Onyx", model = "Palma"))
        assertTrue(DeviceProfile.isOnyxDevice(manufacturer = "Onyx", device = "BooxTabUltra"))
        assertTrue(DeviceProfile.isOnyxDevice(model = "BOOX Poke 5"))

        assertFalse(DeviceProfile.isOnyxDevice(manufacturer = "Kobo", model = "Clara"))
        assertFalse(DeviceProfile.isOnyxDevice(manufacturer = "Hisense", model = "A9"))
        assertFalse(DeviceProfile.isOnyxDevice(manufacturer = "Google", model = "Pixel 9 Pro"))
        assertFalse(DeviceProfile.isOnyxDevice(manufacturer = "Samsung", model = "Galaxy Tab"))
    }

    @Test
    fun `HardwareKeyDispatcher ignores events when reader is inactive`() {
        HardwareKeyDispatcher.isReaderActive = false

        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_UP))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_UP))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `HardwareKeyDispatcher dispatches page turn events when reader is active and quiet`() = runTest {
        HardwareKeyDispatcher.isReaderActive = true
        HardwareKeyDispatcher.volumeKeysTurnPages = true

        val emitted = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            HardwareKeyDispatcher.pageTurnEvents.collect { emitted.add(it) }
        }

        // Next page keys (Volume Down, Page Down, DPAD Right)
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_RIGHT))

        // Previous page keys (Volume Up, Page Up, DPAD Left)
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_UP))
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_UP))
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_LEFT))

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1, 1, 1, -1, -1, -1), emitted)

        job.cancel()
    }

    @Test
    fun `HardwareKeyDispatcher allows dedicated page keys even when volumeKeysTurnPages is false`() = runTest {
        HardwareKeyDispatcher.isReaderActive = true
        HardwareKeyDispatcher.volumeKeysTurnPages = false

        val emitted = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            HardwareKeyDispatcher.pageTurnEvents.collect { emitted.add(it) }
        }

        // Volume keys should NOT be consumed
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_UP))

        // Dedicated Page Up / Page Down keys on Boox devices should still work!
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_UP))

        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1, -1), emitted)

        job.cancel()
    }

    @Test
    fun `HardwareKeyDispatcher does not consume volume keys when audio is playing or in audio-only view`() {
        HardwareKeyDispatcher.isReaderActive = true
        HardwareKeyDispatcher.volumeKeysTurnPages = true

        // Playing narration: volume keys must adjust volume, not turn pages
        HardwareKeyDispatcher.isAudioPlaying = true
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_UP))
        // Dedicated page keys continue to work
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))

        // In Audio-only view
        HardwareKeyDispatcher.isAudioPlaying = false
        HardwareKeyDispatcher.isAudioOnlyMode = true
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `HardwareKeyDispatcher does not consume keys when dialog or overlay is open`() {
        HardwareKeyDispatcher.isReaderActive = true
        HardwareKeyDispatcher.volumeKeysTurnPages = true
        HardwareKeyDispatcher.isOverlayOpen = true

        // When Settings, Notebook, Dictionary, or conflict dialog is open,
        // hardware keys should NOT turn pages under the dialog
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_UP))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_UP))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_RIGHT))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `HardwareKeyDispatcher preserves DPAD for accessibility and switch access when chrome is visible`() {
        HardwareKeyDispatcher.isReaderActive = true
        HardwareKeyDispatcher.volumeKeysTurnPages = true
        HardwareKeyDispatcher.isChromeVisible = true

        // DPAD must NOT be intercepted when chrome is visible so switch access and keyboard users can focus controls
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_RIGHT))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_LEFT))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_UP))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_DOWN))

        // Dedicated page keys and volume keys (when quiet) still turn pages
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))
        assertTrue(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun `HardwareKeyDispatcher ignores keys in continuous scroll mode`() {
        HardwareKeyDispatcher.isReaderActive = true
        HardwareKeyDispatcher.isPagedMode = false

        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_VOLUME_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_PAGE_DOWN))
        assertFalse(HardwareKeyDispatcher.onKeyDown(HardwareKeyDispatcher.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `shouldDisplayDualPage respects TwoPageViewMode across 5 reference devices`() {
        // Pixel Tablet landscape: 1280 x 800 dp -> Dual page
        assertTrue(shouldDisplayDualPage(TwoPageViewMode.AUTO, 1280f, 800f, isBookPosture = false))

        // Pixel Tablet portrait: 800 x 1280 dp -> Single page (width not > height)
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.AUTO, 800f, 1280f, isBookPosture = false))

        // Pixel 9 Pro landscape: 915 x 410 dp -> Single page (height < 480 dp prevents cramped lines)
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.AUTO, 915f, 410f, isBookPosture = false))

        // Pixel 9 Pro portrait: 412 x 915 dp -> Single page
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.AUTO, 412f, 915f, isBookPosture = false))

        // Pixel Pro Fold unfolded landscape: 842 x 742 dp -> Dual page
        assertTrue(shouldDisplayDualPage(TwoPageViewMode.AUTO, 842f, 742f, isBookPosture = false))

        // Pixel Pro Fold book posture (half-opened vertical fold) -> Dual page regardless of dimensions
        assertTrue(shouldDisplayDualPage(TwoPageViewMode.AUTO, 600f, 800f, isBookPosture = true))

        // Pixel Pro Fold folded cover portrait: 412 x 892 dp -> Single page
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.AUTO, 412f, 892f, isBookPosture = false))

        // Onyx Boox Go Color 7 portrait: 600 x 800 dp -> Single page
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.AUTO, 600f, 800f, isBookPosture = false))

        // Onn Tab 7 landscape (compact 7" tablet under 480dp height): 600 x 400 dp -> Single page
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.AUTO, 600f, 400f, isBookPosture = false))

        // TwoPageViewMode.OFF always forces single page
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.OFF, 1280f, 800f, isBookPosture = false))
        assertFalse(shouldDisplayDualPage(TwoPageViewMode.OFF, 600f, 800f, isBookPosture = true))

        // TwoPageViewMode.ON always forces dual page
        assertTrue(shouldDisplayDualPage(TwoPageViewMode.ON, 412f, 915f, isBookPosture = false))
        assertTrue(shouldDisplayDualPage(TwoPageViewMode.ON, 600f, 800f, isBookPosture = false))
    }
}
