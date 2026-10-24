package net.kirkanos.mtgportal.feature.collection

import net.kirkanos.mtgportal.core.data.BinderTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinderTargetTest {
    @Test
    fun roundTrip() {
        for (t in listOf(BinderTarget("Scans", "binder"), BinderTarget("Trades | 2026", "list"), BinderTarget("", "binder"))) {
            assertEquals(t, decodeTarget(encodeTarget(t)))
        }
    }

    @Test
    fun nothingStored() {
        assertNull(decodeTarget(null))
        assertNull(decodeTarget("garbage"))
    }
}
