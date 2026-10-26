package org.hello.reader.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NaturalOrderComparatorTest {

    @Test
    fun `audio tracks sort numerically rather than alphabetically`() {
        val tracks = listOf(
            "Track 10.mp3",
            "Track 2.mp3",
            "Track 1.mp3",
            "Track 20.mp3",
            "Track 3.mp3",
        )
        val sorted = tracks.sortedWith(NaturalOrderComparator)
        assertEquals(
            listOf("Track 1.mp3", "Track 2.mp3", "Track 3.mp3", "Track 10.mp3", "Track 20.mp3"),
            sorted,
        )
    }

    @Test
    fun `multi disc paths sort by directory then natural file order`() {
        val files = listOf(
            File("disc2/01.mp3"),
            File("disc1/10.mp3"),
            File("disc1/02.mp3"),
            File("disc1/01.mp3"),
            File("disc2/10.mp3"),
            File("disc2/02.mp3"),
        )
        val sorted = files.sortedWith(NaturalFileOrder)
        assertEquals(
            listOf(
                File("disc1/01.mp3"),
                File("disc1/02.mp3"),
                File("disc1/10.mp3"),
                File("disc2/01.mp3"),
                File("disc2/02.mp3"),
                File("disc2/10.mp3"),
            ),
            sorted,
        )
    }
}
