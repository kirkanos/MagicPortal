package net.kirkanos.mtgportal.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSoundsTest {
    @Test
    fun tiersGoByPrice() {
        assertEquals(0, ScanSounds.tier(null))
        assertEquals(0, ScanSounds.tier(0.25))
        assertEquals(1, ScanSounds.tier(1.0))
        assertEquals(1, ScanSounds.tier(4.99))
        assertEquals(2, ScanSounds.tier(5.0))
        assertEquals(3, ScanSounds.tier(20.0))
        assertEquals(3, ScanSounds.tier(1500.0))
    }
}
