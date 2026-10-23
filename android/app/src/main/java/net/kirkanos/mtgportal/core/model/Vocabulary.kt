package net.kirkanos.mtgportal.core.model

/** Card conditions, from CONDITION_LABEL / CONDITION_ORDER in shared.js. */
object Condition {
    private val LABEL = mapOf(
        "mint" to "Mint",
        "near_mint" to "Near Mint",
        "excellent" to "Excellent",
        "good" to "Good",
        "light_played" to "Lightly Played",
        "lightly_played" to "Lightly Played",
        "played" to "Played",
        "moderately_played" to "Moderately Played",
        "heavily_played" to "Heavily Played",
        "poor" to "Poor",
        "damaged" to "Damaged",
    )

    /**
     * Best first, as Cardmarket and ManaBox order them. light_played and
     * lightly_played are one grade in two spellings; new input uses the first.
     */
    val ORDER = listOf("mint", "near_mint", "excellent", "good", "light_played", "played", "poor")

    fun label(key: String?): String {
        if (key.isNullOrEmpty()) return "–"
        return LABEL[key.lowercase()]
            ?: key.replace('_', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}

/** Card languages, from LANGUAGE_FLAG in shared.js. */
object Language {
    private val FLAG = mapOf(
        "en" to "🇬🇧", "de" to "🇩🇪", "fr" to "🇫🇷", "it" to "🇮🇹", "es" to "🇪🇸", "pt" to "🇵🇹",
        "ja" to "🇯🇵", "ko" to "🇰🇷", "ru" to "🇷🇺", "zhs" to "🇨🇳", "zht" to "🇹🇼",
    )

    fun flag(lang: String?): String = FLAG[lang.orEmpty().lowercase()].orEmpty()
}

/** Mana cost helpers, from renderManaCost() / manaValue() in shared.js. */
object ManaCost {
    private val TOKEN = Regex("""\{([^}]+)\}""")

    /** "{2}{R}{R}" -> ["2", "R", "R"] */
    fun tokens(cost: String?): List<String> = TOKEN.findAll(cost.orEmpty()).map { it.groupValues[1] }.toList()

    /** Splits rules text into literal text and symbol tokens, in order. */
    fun segments(text: String?): List<Segment> {
        val src = text.orEmpty()
        val out = mutableListOf<Segment>()
        var last = 0
        for (m in TOKEN.findAll(src)) {
            if (m.range.first > last) out += Segment.Text(src.substring(last, m.range.first))
            out += Segment.Symbol(m.groupValues[1])
            last = m.range.last + 1
        }
        if (last < src.length) out += Segment.Text(src.substring(last))
        return out
    }

    sealed interface Segment {
        data class Text(val value: String) : Segment
        data class Symbol(val token: String) : Segment
    }
}
