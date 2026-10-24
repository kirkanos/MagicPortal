package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.PortalApi
import net.kirkanos.mtgportal.core.network.dto.WishlistDto
import net.kirkanos.mtgportal.core.network.dto.WishlistInputDto
import net.kirkanos.mtgportal.core.network.safeCall
import retrofit2.HttpException
import retrofit2.Response

/**
 * The wishlist, shared by every screen that shows a star.
 *
 * The web UI keeps the names in a module-level Set and walks the whole
 * document after each toggle to update every star (syncWishButtons). Here the
 * names are a StateFlow and every star follows it by itself.
 *
 * Names are compared lowercase, like the backend and onWishlist() do.
 */
class WishlistRepository(
    private val api: PortalApi,
    private val auth: AuthRepository,
) {
    private val _names = MutableStateFlow<Set<String>>(emptySet())
    val names: StateFlow<Set<String>> = _names.asStateFlow()

    fun contains(name: String) = name.lowercase() in _names.value

    /** Full list for the wishlist page; also refreshes the names. */
    suspend fun load(): ApiResult<WishlistDto> {
        val r = safeCall { api.wishlist() }
        if (r is ApiResult.Success) _names.value = r.data.items.map { it.name.lowercase() }.toSet()
        return r
    }

    /** Adds (with the whole row, since the endpoint upserts) or removes one card. */
    suspend fun set(name: String, on: Boolean, row: WishlistInputDto = WishlistInputDto(name)): ApiResult<Unit> {
        val r = safeCall { check(if (on) api.upsertWishlist(row) else api.deleteWishlist(name)) }
        when {
            r is ApiResult.Success -> _names.update { if (on) it + name.lowercase() else it - name.lowercase() }
            r is ApiResult.HttpError && r.code == 403 -> auth.onForbidden()
        }
        return r
    }

    /** Response<Unit> carries the status instead of throwing; make errors throw like the rest. */
    private fun check(resp: Response<Unit>) {
        if (!resp.isSuccessful) throw HttpException(resp)
    }
}
