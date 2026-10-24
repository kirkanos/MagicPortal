package net.kirkanos.mtgportal.feature.collection

import net.kirkanos.mtgportal.scanner.MatchSource
import net.kirkanos.mtgportal.scanner.ScanSessionResult
import net.kirkanos.mtgportal.scanner.ScannedCard
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanListTest {
    private fun card(name: String, set: String, num: String, qty: Int = 1, foil: String = "normal") =
        ScannedCard(set, num, "id", name, foil = foil, quantity = qty, source = MatchSource.OCR_SET_NUMBER, confidence = 1f)

    @Test
    fun importFormat() {
        val list = scanAsList(
            ScanSessionResult(
                listOf(
                    card("Lightning Bolt", "2x2", "117", qty = 4),
                    card("Ledger Shredder", "snc", "46", foil = "foil"),
                    card("Bruce Banner // The Incredible Hulk", "msh", "49"),
                ),
            ),
        )
        assertEquals("4 Lightning Bolt (2X2) 117\n1 Ledger Shredder (SNC) 46 *F*\n1 Bruce Banner (MSH) 49", list)
    }
}
