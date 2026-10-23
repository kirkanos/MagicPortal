package net.kirkanos.mtgportal.feature.dashboard

import net.kirkanos.mtgportal.core.network.dto.KeyQty
import net.kirkanos.mtgportal.core.network.dto.MonthValue
import net.kirkanos.mtgportal.core.network.dto.RarityRowDto
import net.kirkanos.mtgportal.core.network.dto.ValuePointDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// The same rules as dashboard.js.
class DashboardLogicTest {
    @Test
    fun cumulativePurchase() =
        assertEquals(listOf(10.0, 15.0, 15.0, 22.5), cumulative(listOf(MonthValue("a", 10.0), MonthValue("b", 5.0), MonthValue("c", 0.0), MonthValue("d", 7.5))))

    @Test
    fun conditionsMergeBySharedLabel() = assertEquals(
        listOf("Near Mint" to 100, "Lightly Played" to 30, "Played" to 5),
        groupConditions(listOf(KeyQty("near_mint", 100), KeyQty("light_played", 10), KeyQty("lightly_played", 20), KeyQty("played", 5))),
    )

    @Test
    fun bucketsKeepTheirOrderAndDropEmptyOnes() = assertEquals(
        listOf("0", "2", "7+"),
        ordered(listOf(KeyQty("7+", 3), KeyQty("2", 9), KeyQty("0", 1), KeyQty("1", 0)), CMC_ORDER).map { it.key },
    )

    @Test
    fun historyRangeCutsByDate() {
        val pts = listOf("2026-01-01", "2026-09-30", "2026-10-20").map { ValuePointDto(it) }
        val today = LocalDate.parse("2026-10-23")
        assertEquals(3, historyRange(pts, 0, today).size)
        assertEquals(listOf("2026-10-20"), historyRange(pts, 7, today).map { it.date })
        assertEquals(listOf("2026-09-30", "2026-10-20"), historyRange(pts, 30, today).map { it.date })
    }

    @Test
    fun rarityTableOrderAvgAndShare() {
        val t = rarityTable(listOf(RarityRowDto("rare", 2, 4, 30.0), RarityRowDto("bonus", 1, 1, 10.0), RarityRowDto("common", 5, 10, 60.0)))
        assertEquals(listOf("common", "rare", "bonus"), t.map { it.key })
        assertEquals(7.5, t[1].avg, 1e-9)
        assertEquals(60.0, t[0].share, 1e-9)
    }
}
