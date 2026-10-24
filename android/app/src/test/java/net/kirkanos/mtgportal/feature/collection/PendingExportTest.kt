package net.kirkanos.mtgportal.feature.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingExportTest {
    @Test
    fun roundTrip() {
        val p = PendingExport(12, 34, "magic-portal-app-12.csv")
        assertEquals(p, PendingExport.decode(p.encode()))
    }

    @Test
    fun noneStored() {
        assertNull(PendingExport.decode(null))
        assertNull(PendingExport.decode(""))
        assertNull(PendingExport.decode("x|y|z"))
    }
}
