package net.kirkanos.mtgportal.feature.cards

import net.kirkanos.mtgportal.core.network.dto.CollectionRowDto
import net.kirkanos.mtgportal.core.network.dto.VariantDto
import org.junit.Assert.assertEquals
import org.junit.Test

class VariantEditsTest {
    private val csvRow = VariantDto(id = 1, origin = "csv", language = "en", foil = "normal", condition = "near_mint", quantity = 2, binderName = "Box", marketPrice = 1.5)
    private val foilRow = VariantDto(id = 2, origin = "csv", foil = "foil", quantity = 1, marketPrice = 9.0)

    @Test
    fun plusOneOnACsvRowAddsAnAppRow() {
        val out = applyWrite(listOf(csvRow, foilRow), CollectionRowDto(id = 5, origin = "app", foil = "normal", language = "en", condition = "near_mint", quantity = 1, binderName = "Box"))
        assertEquals(listOf(1L, 2L, 5L), out.map { it.id })
        // The price of the same finish carries over.
        assertEquals(1.5, out.last().marketPrice!!, 0.0)
    }

    @Test
    fun aChangedRowIsReplaced() {
        val out = applyWrite(listOf(csvRow), CollectionRowDto(id = 1, origin = "csv", foil = "normal", quantity = 1, binderName = "Box"))
        assertEquals(1, out.single().quantity)
    }

    @Test
    fun deletedAndMergedRowsGo() {
        assertEquals(listOf(2L), applyWrite(listOf(csvRow, foilRow), null, setOf(1L)).map { it.id })
        // A move that merged into row 2: row 1 goes, row 2 takes the new total.
        val out = applyWrite(listOf(csvRow, foilRow), CollectionRowDto(id = 2, origin = "csv", foil = "foil", quantity = 3), setOf(1L))
        assertEquals(listOf(2L to 3), out.map { it.id to it.quantity })
    }
}
