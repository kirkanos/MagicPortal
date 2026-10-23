package net.kirkanos.mtgportal.core.model

/**
 * The foil-aware market price of one copy.
 *
 * Kotlin twin of variantMarketPrice() in shared.js, cardRow.marketPrice() in
 * cards.go and foilAwareMarket in valuehistory.go. Foil means anything but
 * "normal" or empty, so "etched" counts as foil too. A foil copy uses the foil
 * price when the catalog has one and falls back to the normal price otherwise.
 *
 * Where the backend already computed the price (variants of /api/cards carry
 * marketPrice), use that value; this is for rows that come without it.
 */
object Pricing {
    fun isFoil(foil: String?): Boolean {
        val f = foil.orEmpty()
        return f.isNotEmpty() && !f.equals("normal", ignoreCase = true)
    }

    fun marketPrice(foil: String?, priceEur: Double?, priceEurFoil: Double?): Double? =
        if (isFoil(foil) && priceEurFoil != null) priceEurFoil else priceEur
}
