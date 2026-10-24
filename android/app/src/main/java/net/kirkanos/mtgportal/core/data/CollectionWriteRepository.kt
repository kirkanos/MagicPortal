package net.kirkanos.mtgportal.core.data

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.PortalApi
import net.kirkanos.mtgportal.core.network.dto.AddCardItemDto
import net.kirkanos.mtgportal.core.network.dto.AddCardsRequest
import net.kirkanos.mtgportal.core.network.dto.AddCardsResultDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchDto
import net.kirkanos.mtgportal.core.network.dto.RowPatchResultDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.network.safeStatus
import net.kirkanos.mtgportal.scanner.ScanSessionResult

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

    suspend fun addScan(result: ScanSessionResult, target: BinderTarget, condition: String, sessionKey: String): ApiResult<AddCardsResultDto> {
        val body = toRequest(result, target, condition)
        var attempt = 0
        while (true) {
            val r = safeCall { api.addCards(body, sessionKey) }
            if (r !is ApiResult.NetworkError || ++attempt >= 3) return r.withReadableError()
            delay(1000L * attempt)
        }
    }

    suspend fun patch(id: Long, patch: RowPatchDto): ApiResult<RowPatchResultDto> = safeCall { api.patchRow(id, patch) }.withReadableError()

    suspend fun delete(id: Long): ApiResult<Unit> = safeStatus { api.deleteRow(id) }.withReadableError()

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
