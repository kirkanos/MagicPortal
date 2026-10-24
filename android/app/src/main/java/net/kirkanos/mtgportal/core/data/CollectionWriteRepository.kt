package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.PortalApi
import net.kirkanos.mtgportal.core.network.dto.AddCardItemDto
import net.kirkanos.mtgportal.core.network.dto.AddCardsRequest
import net.kirkanos.mtgportal.core.network.dto.AddCardsResultDto
import net.kirkanos.mtgportal.core.network.dto.BinderPatchDto
import net.kirkanos.mtgportal.core.network.dto.BinderPatchResultDto
import net.kirkanos.mtgportal.core.network.dto.CollectionRowDto
import net.kirkanos.mtgportal.core.network.dto.ConfirmExportDto
import net.kirkanos.mtgportal.core.network.dto.VariantDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchResultDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.network.safeStatus
import net.kirkanos.mtgportal.scanner.ScanSessionResult
import retrofit2.HttpException
import java.util.UUID

/** Where scanned cards go. An empty name is the collection without a binder. */
data class BinderTarget(val name: String, val type: String = "binder")

/**
 * Writes to the collection (POST/PATCH/DELETE /api/collection/cards).
 *
 * The cards are written as origin 'app': the next ManaBox CSV import keeps
 * them. A scan session goes over in one request with the session's ID as
 * idempotency key, so retrying after a dropped connection - here, up to
 * three times - can never add the cards twice.
 */
class CollectionWriteRepository(private val api: PortalApi) {

    /**
     * Counts successful writes. Screens that show collection data stay in
     * memory while the user is elsewhere; they reload when this changes, so
     * a change made on another screen (a handover, a scan) is never shown
     * stale - and never edited through a row id that no longer exists.
     */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    private fun <T> ApiResult<T>.noted(): ApiResult<T> = also { if (it is ApiResult.Success) _changes.update { n -> n + 1 } }

    suspend fun addScan(result: ScanSessionResult, target: BinderTarget, condition: String, sessionKey: String): ApiResult<AddCardsResultDto> {
        val body = toRequest(result, target, condition)
        var attempt = 0
        while (true) {
            val r = safeCall { api.addCards(body, sessionKey) }
            if (r !is ApiResult.NetworkError || ++attempt >= 3) return r.withReadableError().noted()
            delay(1000L * attempt)
        }
    }

    /** One more copy of an owned variant, as an app row: unlike +1 on a csv row it survives the next import. */
    suspend fun addOne(setCode: String, collectorNumber: String, v: VariantDto): ApiResult<CollectionRowDto> = safeCall {
        val item = AddCardItemDto(setCode.lowercase(), collectorNumber, 1, v.foil.ifEmpty { "normal" }, v.language.ifEmpty { "en" }, v.condition.ifEmpty { "near_mint" }, v.binderName, v.binderType)
        api.addCards(AddCardsRequest(listOf(item)), UUID.randomUUID().toString()).items.first()
    }.withReadableError().noted()

    suspend fun patch(id: Long, patch: RowPatchDto): ApiResult<RowPatchResultDto> = safeCall { api.patchRow(id, patch) }.withReadableError().noted()

    /** A ManaBox export of the app's cards, to hand them over. */
    data class Export(val id: Long, val cards: Int, val csv: ByteArray)

    suspend fun exportForManabox(): ApiResult<Export> = safeCall {
        val r = api.exportCollection()
        if (!r.isSuccessful) throw HttpException(r)
        val id = r.headers()["X-Export-Id"]?.toLongOrNull() ?: error("no export id")
        Export(id, r.headers()["X-Export-Cards"]?.toIntOrNull() ?: 0, r.body()?.bytes() ?: ByteArray(0))
    }.withReadableError()

    suspend fun confirmExport(id: Long): ApiResult<ConfirmExportDto> = safeCall { api.confirmExport(id) }.withReadableError().noted()

    suspend fun renameBinder(name: String, patch: BinderPatchDto): ApiResult<BinderPatchResultDto> = safeCall { api.patchBinder(name, patch) }.withReadableError().noted()

    suspend fun delete(id: Long): ApiResult<Unit> = safeStatus { api.deleteRow(id) }.withReadableError().noted()

    companion object {
        fun toRequest(result: ScanSessionResult, target: BinderTarget, condition: String) = AddCardsRequest(
            result.items.map { c ->
                AddCardItemDto(
                    setCode = c.setCode,
                    collectorNumber = c.collectorNumber,
                    quantity = c.quantity,
                    foil = c.foil,
                    language = c.language,
                    condition = condition,
                    binderName = target.name,
                    binderType = if (target.name.isEmpty()) "" else target.type,
                )
            },
        )

        /** The write API answers errors as {"error": "...", "index": n}. */
        internal fun <T> ApiResult<T>.withReadableError(): ApiResult<T> {
            if (this !is ApiResult.HttpError) return this
            val obj = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return this
            val msg = obj["error"]?.jsonPrimitive?.content ?: return this
            val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull()
            return copy(message = if (index != null) "$msg (#${index + 1})" else msg)
        }
    }
}
