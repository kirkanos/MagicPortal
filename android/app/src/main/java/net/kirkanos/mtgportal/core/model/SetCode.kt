package net.kirkanos.mtgportal.core.model

/**
 * Set codes come in two spellings from the same backend: UPPERCASE in cards,
 * collection and prints, lowercase as the keys of /api/sets and in the
 * database. Every lookup goes through [key]; raw strings are never compared.
 */
object SetCode {
    fun key(code: String?): String = code.orEmpty().trim().lowercase()

    fun display(code: String?): String = code.orEmpty().trim().uppercase()
}
