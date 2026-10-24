package net.kirkanos.mtgportal.core.network

import net.kirkanos.mtgportal.core.network.dto.ActivityDto
import net.kirkanos.mtgportal.core.network.dto.AddCardsRequest
import net.kirkanos.mtgportal.core.network.dto.AddCardsResultDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchResultDto
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.dto.BinderPatchDto
import net.kirkanos.mtgportal.core.network.dto.BinderPatchResultDto
import net.kirkanos.mtgportal.core.network.dto.ConfirmExportDto
import net.kirkanos.mtgportal.core.network.dto.ImportResultDto
import net.kirkanos.mtgportal.core.network.dto.UiConfigInputDto
import net.kirkanos.mtgportal.core.network.dto.CardGroupDto
import net.kirkanos.mtgportal.core.network.dto.CardsPageDto
import net.kirkanos.mtgportal.core.network.dto.DashboardDto
import net.kirkanos.mtgportal.core.network.dto.MoversDto
import net.kirkanos.mtgportal.core.network.dto.ValuePointDto
import net.kirkanos.mtgportal.core.network.dto.EditionDto
import net.kirkanos.mtgportal.core.network.dto.FacetsDto
import net.kirkanos.mtgportal.core.network.dto.OwnedByNameRequest
import net.kirkanos.mtgportal.core.network.dto.PrintDto
import net.kirkanos.mtgportal.core.network.dto.ReservedDto
import net.kirkanos.mtgportal.core.network.dto.SetCardDto
import net.kirkanos.mtgportal.core.network.dto.SetDto
import net.kirkanos.mtgportal.core.network.dto.StatusDto
import net.kirkanos.mtgportal.core.network.dto.UiConfigDto
import net.kirkanos.mtgportal.core.network.dto.WishlistDto
import net.kirkanos.mtgportal.core.network.dto.WishlistInputDto
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * The backend's routes (backend/main.go), one to one. Paths are relative to
 * "<base>/api/"; [BaseUrlInterceptor] fills in the configured server.
 *
 * Screens arrive one by one, and with them the routes they use - this file
 * grows with them rather than being written ahead.
 */
interface PortalApi {

    @GET("health")
    suspend fun health(): Response<Unit>

    /**
     * 204 when the request is authorised (or no password is configured at
     * all), 403 otherwise. The header is passed explicitly, so the
     * [AuthInterceptor] stays out of it - that is the whole point of a probe.
     */
    @GET("auth-check")
    suspend fun authCheck(
        @Header(AuthInterceptor.HEADER) password: String?,
        @Tag mode: AuthMode = AuthMode.EXPLICIT,
    ): Response<Unit>

    @GET("config")
    suspend fun config(): UiConfigDto

    @GET("status")
    suspend fun status(): StatusDto

    @GET("cards")
    suspend fun cards(
        @Query("q") q: String? = null,
        @Query("set") set: String? = null,
        @Query("rarity") rarity: String? = null,
        @Query("color") color: String? = null,
        @Query("foil") foil: String? = null,
        @Query("binder") binder: String? = null,
        @Query("type") type: String? = null,
        @Query("subtype") subtype: String? = null,
        @Query("sort") sort: String? = null,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int? = null,
    ): CardsPageDto

    @GET("cards/facets")
    suspend fun facets(): FacetsDto

    @GET("cards/random")
    suspend fun randomCards(@Query("count") count: Int): List<CardGroupDto>

    @GET("subtypes")
    suspend fun subtypes(): List<String>

    @GET("sets")
    suspend fun sets(): Map<String, SetDto>

    @GET("sets/{code}/cards")
    suspend fun setCards(@Path("code") code: String): List<SetCardDto>

    @GET("prints")
    suspend fun prints(@Query("name") name: String): List<PrintDto>

    @GET("binders")
    suspend fun binders(): List<BinderDto>

    @GET("editions")
    suspend fun editions(): List<EditionDto>

    /** Owned copies per lowercase name; every asked name is in the answer. */
    @POST("collection/owned")
    suspend fun ownedByName(@Body body: OwnedByNameRequest): Map<String, Int>

    /**
     * Adds a batch in one transaction. The same [idempotencyKey] (one per
     * scan session) makes a retry after a lost answer return the first
     * result instead of adding the cards twice.
     */
    @POST("collection/cards")
    suspend fun addCards(@Body body: AddCardsRequest, @Header("Idempotency-Key") idempotencyKey: String): AddCardsResultDto

    @PATCH("collection/cards/{id}")
    suspend fun patchRow(@Path("id") id: Long, @Body body: RowPatchDto): RowPatchResultDto

    @DELETE("collection/cards/{id}")
    suspend fun deleteRow(@Path("id") id: Long): Response<Unit>

    /** The app's cards as a ManaBox CSV; the export ID comes in X-Export-Id. */
    @POST("collection/export")
    suspend fun exportCollection(): Response<ResponseBody>

    @POST("collection/export/{id}/confirm")
    suspend fun confirmExport(@Path("id") id: Long): ConfirmExportDto

    /** The name is one path segment; Retrofit encodes "/" in it. */
    @PATCH("binders/{name}")
    suspend fun patchBinder(@Path("name") name: String, @Body body: BinderPatchDto): BinderPatchResultDto

    @GET("wishlist")
    suspend fun wishlist(): WishlistDto

    @POST("wishlist")
    suspend fun upsertWishlist(@Body item: WishlistInputDto): Response<Unit>

    /** The name is one path segment; Retrofit encodes "/" in "Fire // Ice". */
    @DELETE("wishlist/{name}")
    suspend fun deleteWishlist(@Path("name") name: String): Response<Unit>

    /** Cardmarket decklist format, text/plain. */
    @GET("wishlist/export")
    suspend fun wishlistExport(): String

    @GET("reserved")
    suspend fun reserved(): ReservedDto

    @GET("dashboard")
    suspend fun dashboard(): DashboardDto

    @GET("value-history")
    suspend fun valueHistory(): List<ValuePointDto>

    @GET("value-movers")
    suspend fun valueMovers(@Query("days") days: Int): MoversDto

    // ---- Admin: everything below needs the upload password ----

    @POST("config")
    suspend fun setConfig(@Body cfg: UiConfigInputDto): UiConfigDto

    @GET("activity")
    suspend fun activity(): List<ActivityDto>

    @POST("activity/clear")
    suspend fun clearActivity(): Response<Unit>

    /** Card data and prices from Scryfall; also checks for a remote CSV. Returns at once. */
    @POST("sync")
    suspend fun sync(): Response<Unit>

    @POST("images/sync")
    suspend fun imagesSync(): Response<Unit>

    @POST("backup")
    suspend fun backup(): Response<Unit>

    @POST("backup/restore-latest")
    suspend fun restoreLatest(): Response<Unit>

    @POST("reset")
    suspend fun reset(): Response<Unit>

    /** Raw CSV as the body, like the web's file upload. Replaces the collection. */
    @POST("upload")
    suspend fun upload(@Body csv: RequestBody): ImportResultDto
}
