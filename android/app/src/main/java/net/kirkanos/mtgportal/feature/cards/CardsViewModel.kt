package net.kirkanos.mtgportal.feature.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.core.model.SetCode
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import net.kirkanos.mtgportal.core.network.dto.FacetsDto
import net.kirkanos.mtgportal.core.network.dto.SetDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.di.AppContainer

/** The filter row of the web grid (currentParams in grid.js). Empty = no filter. */
data class CardFilters(
    val query: String = "",
    val set: String = "",
    val rarity: String = "",
    val color: String = "",
    val foil: String = "",
    val binder: String = "",
    val type: String = "",
    val subtype: String = "",
    val sort: String = "name",
) {
    /** Filters behind the "Filter" button, for its badge. Search and sort are visible anyway. */
    val activeCount: Int
        get() = listOf(set, rarity, color, foil, binder, type, subtype).count { it.isNotEmpty() }
}

data class CardsUiState(
    val filters: CardFilters = CardFilters(),
    val facets: FacetsDto? = null,
    /** /api/sets, keyed lowercase: set names and icons for tiles and picker. */
    val sets: Map<String, SetDto> = emptyMap(),
    /** Subtypes the selected type offers (cascade like populateSubtypes in grid.js). */
    val subtypes: List<String> = emptyList(),
    val groups: List<CardGroupDto> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: ApiResult<Nothing>? = null,
    val random: List<CardGroupDto> = emptyList(),
)

@OptIn(FlowPreview::class)
class CardsViewModel(
    private val c: AppContainer,
    initialBinder: String?,
    initialSet: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        CardsUiState(
            filters = CardFilters(
                binder = initialBinder.orEmpty(),
                set = initialSet?.let(SetCode::display).orEmpty(),
            ),
        ),
    )
    val state: StateFlow<CardsUiState> = _state.asStateFlow()

    private var officialSubtypes: Set<String> = emptySet()
    private var queryJob: Job? = null
    private var pageJob: Job? = null

    init {
        loadAll()
        // Typing is debounced like the web's 250 ms; everything else applies at once.
        viewModelScope.launch {
            _state.map { it.filters.query }.distinctUntilChanged().drop(1).debounce(250).collect { runQuery() }
        }
    }

    /** Facets, sets and subtypes in parallel, then the first page. */
    fun loadAll() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val facets = async { safeCall { c.api.facets() } }
            val sets = async { safeCall { c.api.sets() } }
            val subs = async { safeCall { c.api.subtypes() } }
            when (val f = facets.await()) {
                is ApiResult.Success -> _state.update { it.copy(facets = f.data) }
                is ApiResult.HttpError -> return@launch _state.update { it.copy(loading = false, error = f) }
                is ApiResult.NetworkError -> return@launch _state.update { it.copy(loading = false, error = f) }
            }
            (sets.await() as? ApiResult.Success)?.let { s -> _state.update { it.copy(sets = s.data) } }
            (subs.await() as? ApiResult.Success)?.let { officialSubtypes = it.data.toSet() }
            _state.update { it.copy(subtypes = subtypesFor(it.filters.type)) }
            runQuery()
            loadRandom()
        }
    }

    fun loadRandom() {
        viewModelScope.launch {
            val cfg = c.config.config.value
            if (!cfg.randomEnabled) return@launch _state.update { it.copy(random = emptyList()) }
            // Decoration: if it fails, the page stays usable without it.
            val r = safeCall { c.api.randomCards(cfg.randomCount.coerceIn(1, 30)) }
            if (r is ApiResult.Success) _state.update { it.copy(random = r.data) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(filters = it.filters.copy(query = q)) }

    /** Any filter except the search text: applies immediately. */
    fun setFilters(transform: (CardFilters) -> CardFilters) {
        _state.update { st ->
            var f = transform(st.filters)
            // A new type invalidates the subtype, like the cascade on the web.
            if (f.type != st.filters.type) f = f.copy(subtype = "")
            st.copy(filters = f, subtypes = subtypesFor(f.type))
        }
        runQuery()
    }

    fun resetFilters() = setFilters { CardFilters(query = it.query, sort = it.sort) }

    private fun subtypesFor(type: String): List<String> {
        if (type.isEmpty()) return emptyList()
        val all = _state.value.facets?.subtypesByType?.get(type).orEmpty()
        // Only official subtypes; the raw type lines also yield words like "of".
        return if (officialSubtypes.isEmpty()) all else all.filter { it in officialSubtypes }
    }

    /** A new query replaces the old one; a still running one is cancelled. */
    private fun runQuery() {
        queryJob?.cancel()
        pageJob?.cancel()
        queryJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = fetch(offset = 0)) {
                is ApiResult.Success -> _state.update {
                    it.copy(groups = r.data.groups, total = r.data.total, hasMore = r.data.hasMore, loading = false)
                }
                is ApiResult.HttpError -> _state.update { it.copy(loading = false, error = r) }
                is ApiResult.NetworkError -> _state.update { it.copy(loading = false, error = r) }
            }
        }
    }

    /** Next page when the list nears its end; only one at a time. */
    fun loadMore() {
        val st = _state.value
        if (!st.hasMore || st.loading || st.loadingMore) return
        pageJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val r = fetch(offset = st.groups.size)
            _state.update {
                if (r is ApiResult.Success) {
                    it.copy(groups = it.groups + r.data.groups, hasMore = r.data.hasMore, total = r.data.total, loadingMore = false)
                } else {
                    it.copy(loadingMore = false)
                }
            }
        }
    }

    private suspend fun fetch(offset: Int) = _state.value.filters.let { f ->
        safeCall {
            c.api.cards(
                q = f.query.trim().ifEmpty { null },
                set = f.set.ifEmpty { null },
                rarity = f.rarity.ifEmpty { null },
                color = f.color.ifEmpty { null },
                foil = f.foil.ifEmpty { null },
                binder = f.binder.ifEmpty { null },
                type = f.type.ifEmpty { null },
                subtype = f.subtype.ifEmpty { null },
                sort = f.sort,
                offset = offset,
            )
        }
    }
}
