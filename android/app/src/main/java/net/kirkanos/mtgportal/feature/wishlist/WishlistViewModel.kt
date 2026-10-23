package net.kirkanos.mtgportal.feature.wishlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.WishlistDto
import net.kirkanos.mtgportal.core.network.dto.WishlistInputDto
import net.kirkanos.mtgportal.core.network.dto.WishlistItemDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.di.AppContainer

/** Filter and sort of renderList() in wishlist.js, unchanged. */
internal fun filterWishlist(items: List<WishlistItemDto>, query: String, state: String, sort: String): List<WishlistItemDto> {
    val q = query.trim().lowercase()
    val price = { i: WishlistItemDto -> i.price ?: 0.0 }
    val list = items
        .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.note.lowercase().contains(q) }
        .filter {
            when (state) {
                "target" -> it.targetMet
                "upgrade" -> it.needsUpgrade
                "missing" -> it.ownedQuantity == 0
                else -> true
            }
        }
    return when (sort) {
        "price-asc" -> list.sortedWith(compareBy(price).thenBy { it.name })
        "price-desc" -> list.sortedWith(compareByDescending(price).thenBy { it.name })
        // Entries without history sort last: they say nothing about a drop.
        "change-asc" -> list.sortedBy { it.changePct ?: 1e9 }
        "name" -> list.sortedBy { it.name }
        "added" -> list.sortedByDescending { it.added }
        else -> list // the backend already sorts by priority, then price
    }
}

/** The whole row, because POST /api/wishlist replaces the entry (saveField in wishlist.js). */
internal fun WishlistItemDto.toInput() = WishlistInputDto(
    name = name,
    setCode = setCode,
    collectorNumber = collectorNumber,
    targetEur = targetEur,
    minCondition = minCondition,
    note = note,
    priority = priority,
)

data class WishlistUiState(
    val data: ApiResult<WishlistDto>? = null,
    /** Export text after a successful copy, shown so it can be read back. */
    val exportText: String? = null,
    val message: String? = null,
)

class WishlistViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(WishlistUiState())
    val state: StateFlow<WishlistUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        if (_state.value.data == null) _state.update { it.copy(data = null) }
        _state.update { it.copy(data = c.wishlist.load()) }
    }

    /** Target comparison and summary are computed server-side, so reload after a save. */
    fun save(row: WishlistInputDto) = viewModelScope.launch {
        report(c.wishlist.set(row.name, true, row))
        load()
    }

    fun remove(name: String) = viewModelScope.launch {
        report(c.wishlist.set(name, false))
        load()
    }

    fun add(name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        report(c.wishlist.set(n, true))
        load()
    }

    fun export(onText: (String?) -> Unit) = viewModelScope.launch {
        when (val r = safeCall { c.api.wishlistExport() }) {
            is ApiResult.Success -> {
                val text = r.data.takeIf { it.isNotBlank() }
                _state.update { it.copy(exportText = text) }
                onText(text)
            }
            else -> { report(r); onText(null) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun report(r: ApiResult<*>) {
        val msg = when (r) {
            is ApiResult.HttpError -> "HTTP ${r.code}: ${r.message}"
            is ApiResult.NetworkError -> r.cause.message ?: r.cause.javaClass.simpleName
            is ApiResult.Success -> null
        }
        if (msg != null) _state.update { it.copy(message = msg) }
    }
}
