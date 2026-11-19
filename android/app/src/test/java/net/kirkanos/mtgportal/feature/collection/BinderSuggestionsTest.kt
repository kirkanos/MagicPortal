package net.kirkanos.mtgportal.feature.collection

import net.kirkanos.mtgportal.core.network.dto.BinderDto
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderSuggestionsTest {
    private val binders = listOf(BinderDto("Box", "binder"), BinderDto("Old", "list"), BinderDto("Empty", "binder"), BinderDto(""))
    private val sets = mapOf(
        "Box" to mapOf("M11" to 3, "ICE" to 1),
        "Old" to mapOf("ICE" to 5),
        "" to mapOf("M11" to 9),
    )

    @Test
    fun mostCopiesFirstOverAllGivenEditions() {
        val got = binderSuggestions(binders, sets, listOf("ice", "m11"))
        assertEquals(listOf("Old" to 5, "Box" to 4), got.map { it.first.name to it.second })
    }

    @Test
    fun noEditionsNoSuggestions() {
        assertEquals(emptyList<Pair<BinderDto, Int>>(), binderSuggestions(binders, sets, emptyList()))
        assertEquals(emptyList<Pair<BinderDto, Int>>(), binderSuggestions(binders, sets, listOf("znr")))
    }
}
