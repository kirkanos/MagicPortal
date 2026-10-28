package net.kirkanos.mtgportal.core.model

/**
 * Builds every image address, the way shared.js does (scryfallImageURL,
 * setSymbolURL, manaSymbolImg) - and like there, nowhere else.
 *
 * The backend sends Scryfall ids, never URLs. Scryfall's scheme is fixed:
 *   <base>/<size>/<face>/<id[0]>/<id[1]>/<id>.jpg
 * With the image mirror on (/api/config.imageMirror), the base is the portal's
 * own /img/cards and nginx decides per file whether it serves the local copy or
 * fetches it from Scryfall - the app does not need to know which.
 *
 * @param portalBaseUrl e.g. "https://mtg.example.org", without trailing slash
 */
class ImageUrls(portalBaseUrl: String, private val mirror: Boolean) {

    enum class Size(val path: String) { SMALL("small"), NORMAL("normal") }

    enum class Face(val path: String) { FRONT("front"), BACK("back") }

    private val portal = portalBaseUrl.trimEnd('/')
    private val cardBase = if (mirror) "$portal/img/cards" else SCRYFALL_CARDS

    /**
     * Address of a card image, or null when none can exist: no id, an art
     * printing without catalog image ([noImage]), or the back of a card that
     * has none.
     */
    fun card(
        id: String?,
        size: Size,
        face: Face = Face.FRONT,
        hasBack: Boolean = false,
        noImage: Boolean = false,
    ): String? {
        if (noImage || id == null || id.length < 2) return null
        if (face == Face.BACK && !hasBack) return null
        return "$cardBase/${size.path}/${face.path}/${id[0]}/${id[1]}/$id.jpg"
    }

    /** Set symbol: the mirror keeps the file name of Scryfall's icon URI. */
    fun setIcon(iconSvgUri: String?): String? {
        if (iconSvgUri.isNullOrEmpty()) return null
        if (!mirror) return iconSvgUri
        val file = iconSvgUri.substringAfterLast('/').substringBefore('?')
        return "$portal/img/sets/$file"
    }

    /** A mana or text symbol token without braces, e.g. "W", "2", "W/U", "T". */
    fun manaSymbol(token: String): String {
        val code = token.uppercase().replace("/", "")
        return if (mirror) "$portal/img/symbols/$code.svg" else "$SCRYFALL_SYMBOLS/$code.svg"
    }

    companion object {
        const val SCRYFALL_CARDS = "https://cards.scryfall.io"
        const val SCRYFALL_SYMBOLS = "https://svgs.scryfall.io/card-symbols"
    }
}
