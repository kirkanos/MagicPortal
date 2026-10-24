package net.kirkanos.mtgportal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PricingTest {
    // The same table as variantMarketPrice() in shared.js and cardRow.marketPrice() in cards.go.
    @Test
    fun foilAwareMarketPrice() {
        assertEquals(1.0, Pricing.marketPrice("normal", 1.0, 5.0)!!, 0.0)
        assertEquals(1.0, Pricing.marketPrice("", 1.0, 5.0)!!, 0.0)
        assertEquals(1.0, Pricing.marketPrice(null, 1.0, 5.0)!!, 0.0)
        assertEquals(1.0, Pricing.marketPrice("NORMAL", 1.0, 5.0)!!, 0.0)
        assertEquals(5.0, Pricing.marketPrice("foil", 1.0, 5.0)!!, 0.0)
        assertEquals(5.0, Pricing.marketPrice("etched", 1.0, 5.0)!!, 0.0)
        // Foil without a foil price falls back to the normal price ...
        assertEquals(1.0, Pricing.marketPrice("foil", 1.0, null)!!, 0.0)
        // ... and a normal copy never uses the foil price.
        assertNull(Pricing.marketPrice("normal", null, 5.0))
        assertNull(Pricing.marketPrice("foil", null, null))
    }
}

class ImageUrlsTest {
    private val id = "4d7424b6-b56a-47b7-8204-294d3dca925f"

    @Test
    fun scryfallSchemeWithoutMirror() {
        val u = ImageUrls("https://mtg.kirkanos.net", mirror = false)
        assertEquals("https://cards.scryfall.io/small/front/4/d/$id.jpg", u.card(id, ImageUrls.Size.SMALL))
        assertEquals(
            "https://cards.scryfall.io/normal/back/4/d/$id.jpg",
            u.card(id, ImageUrls.Size.NORMAL, ImageUrls.Face.BACK, hasBack = true),
        )
    }

    @Test
    fun mirrorGoesThroughThePortal() {
        val u = ImageUrls("http://127.0.0.1:8088/", mirror = true)
        assertEquals("http://127.0.0.1:8088/img/cards/small/front/4/d/$id.jpg", u.card(id, ImageUrls.Size.SMALL))
    }

    @Test
    fun noUrlWhereNoImageCanExist() {
        val u = ImageUrls("https://mtg.kirkanos.net", mirror = false)
        assertNull(u.card(null, ImageUrls.Size.SMALL))
        assertNull(u.card("", ImageUrls.Size.SMALL))
        assertNull(u.card("4", ImageUrls.Size.SMALL))
        assertNull(u.card(id, ImageUrls.Size.SMALL, noImage = true))
        assertNull(u.card(id, ImageUrls.Size.NORMAL, ImageUrls.Face.BACK, hasBack = false))
    }

    @Test
    fun setIconKeepsTheFileNameWithoutCacheBuster() {
        val uri = "https://svgs.scryfall.io/sets/snc.svg?1700000000"
        assertEquals(uri, ImageUrls("https://mtg.kirkanos.net", false).setIcon(uri))
        assertEquals("https://mtg.kirkanos.net/img/sets/snc.svg", ImageUrls("https://mtg.kirkanos.net", true).setIcon(uri))
        assertNull(ImageUrls("https://mtg.kirkanos.net", true).setIcon(""))
    }

    @Test
    fun manaSymbolDropsSlashes() {
        assertEquals("https://svgs.scryfall.io/card-symbols/WU.svg", ImageUrls("https://x", false).manaSymbol("w/u"))
        assertEquals("https://x/img/symbols/2.svg", ImageUrls("https://x", true).manaSymbol("2"))
    }
}

class VocabularyTest {
    @Test
    fun conditionLabels() {
        assertEquals("Near Mint", Condition.label("near_mint"))
        assertEquals("Lightly Played", Condition.label("light_played"))
        assertEquals("Lightly Played", Condition.label("LIGHTLY_PLAYED"))
        assertEquals("Some Odd Grade", Condition.label("some_odd_grade"))
        assertEquals("–", Condition.label(""))
    }

    @Test
    fun languageFlags() {
        assertEquals("🇩🇪", Language.flag("DE"))
        assertEquals("🇨🇳", Language.flag("zhs"))
        assertEquals("", Language.flag("xx"))
    }

    @Test
    fun manaCost() {
        assertEquals(listOf("2", "R", "W/U"), ManaCost.tokens("{2}{R}{W/U}"))
        assertEquals(emptyList<String>(), ManaCost.tokens(null))
        assertEquals(
            listOf(
                ManaCost.Segment.Symbol("T"),
                ManaCost.Segment.Text(": Add "),
                ManaCost.Segment.Symbol("G"),
                ManaCost.Segment.Text("."),
            ),
            ManaCost.segments("{T}: Add {G}."),
        )
    }

    @Test
    fun setCodes() {
        assertEquals("snc", SetCode.key(" SNC "))
        assertEquals("SNC", SetCode.display("snc"))
    }
}
