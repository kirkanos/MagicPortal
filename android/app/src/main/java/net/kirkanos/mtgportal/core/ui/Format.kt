package net.kirkanos.mtgportal.core.ui

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Numbers and money are always formatted German, in both UI languages - the
 * web UI does the same (formatCurrency and toLocaleString('de-DE') in
 * shared.js), and the two should show identical figures.
 */
object Format {
    private val locale = Locale.GERMANY

    fun eur(value: Double?): String {
        if (value == null || value.isNaN()) return "-"
        return NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("EUR") }.format(value)
    }

    fun count(value: Int): String = NumberFormat.getIntegerInstance(locale).format(value)
}
