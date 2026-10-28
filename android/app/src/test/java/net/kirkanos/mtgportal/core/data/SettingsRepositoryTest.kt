package net.kirkanos.mtgportal.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun normalizesTypedAddresses() {
        assertEquals("https://mtg.example.org", SettingsRepository.normalizeUrl(" mtg.example.org/ "))
        assertEquals("https://example.org/mtg", SettingsRepository.normalizeUrl("example.org/mtg"))
        assertEquals("http://127.0.0.1:8088", SettingsRepository.normalizeUrl("http://127.0.0.1:8088/"))
        assertEquals("", SettingsRepository.normalizeUrl("  "))
    }
}
