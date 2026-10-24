package net.kirkanos.mtgportal.feature.collection

import net.kirkanos.mtgportal.scanner.ScanSessionResult

/**
 * A scan session as a card list in the common import format
 * ("4 Lightning Bolt (2X2) 117", foil marked *F*), which Moxfield, Archidekt
 * and the deck checker read. Double-faced cards go by their front face, as
 * those importers expect.
 */
fun scanAsList(result: ScanSessionResult): String = result.items.joinToString("\n") { c ->
    val name = c.name.substringBefore(" // ")
    val finish = when (c.foil) {
        "foil" -> " *F*"
        "etched" -> " *E*"
        else -> ""
    }
    "${c.quantity} $name (${c.setCode.uppercase()}) ${c.collectorNumber}$finish"
}
