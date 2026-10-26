package org.hello.reader.feature.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryLayoutTest {

    @Test
    fun `grid fits 96dp cells inside 20dp gutters with 12dp gaps`() {
        assertEquals(1, gridColumnsFor(0.dp))
        assertEquals(1, gridColumnsFor(200.dp))
        // 360dp phone: 320dp usable -> (320 + 12) / 108 = 3.07
        assertEquals(3, gridColumnsFor(360.dp))
        // 412dp phone: 372dp usable -> 3.55
        assertEquals(3, gridColumnsFor(412.dp))
        // 600dp tablet: 560dp usable -> 5.3
        assertEquals(5, gridColumnsFor(600.dp))
        // exact fit: 3 cells + 2 gaps + gutters = 3*96 + 2*12 + 40 = 352dp
        assertEquals(3, gridColumnsFor(352.dp))
        assertEquals(2, gridColumnsFor(351.dp))
    }
}
