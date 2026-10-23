package net.kirkanos.mtgportal.core.network.dto

import kotlinx.serialization.Serializable

// Wire types, field names exactly as the backend sends them. Every field the
// Go side marks omitempty has a default here, and the decoder ignores unknown
// keys, so a backend that adds fields never breaks an older app.
//
// Source structs: groupOut/variantOut (backend/cards.go), setOut, setCardOut,
// printOut, binderOut (backend/main.go), editionOut (backend/editions.go).

@Serializable
data class CardsPageDto(
    val groups: List<CardGroupDto> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val hasMore: Boolean = false,
)

/** One tile of the grid: one printing, its languages/finishes/conditions merged. */
@Serializable
data class CardGroupDto(
    val key: String,
    val name: String,
    val setCode: String = "",
    val setName: String = "",
    val collectorNumber: String = "",
    val rarity: String = "",
    val totalQty: Int = 0,
    val anyFoil: Boolean = false,
    val langs: List<String> = emptyList(),
    val marketPrice: Double = 0.0,
    val purchasePrice: Double = 0.0,
    val added: String = "",
    val released: String = "",
    val imageId: String = "",
    val hasBack: Boolean = false,
    val typeLine: String = "",
    val manaCost: String = "",
    val oracleText: String = "",
    val colors: List<String> = emptyList(),
    val variants: List<VariantDto> = emptyList(),
)

@Serializable
data class VariantDto(
    val language: String = "",
    val foil: String = "normal",
    val condition: String = "",
    val quantity: Int = 0,
    val purchasePrice: Double = 0.0,
    val currency: String = "EUR",
    val added: String = "",
    val binderName: String = "",
    val binderType: String = "",
    /** Null when the printing is unknown to the catalog. */
    val marketPrice: Double? = null,
)

@Serializable
data class FacetsDto(
    val sets: List<FacetSetDto> = emptyList(),
    val rarities: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
    val colorless: Boolean = false,
    val binders: List<String> = emptyList(),
    val foils: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val subtypesByType: Map<String, List<String>> = emptyMap(),
    val stats: FacetStatsDto = FacetStatsDto(),
)

@Serializable
data class FacetSetDto(val code: String, val name: String)

@Serializable
data class FacetStatsDto(
    val entries: Int = 0,
    val distinct: Int = 0,
    val totalQty: Int = 0,
    val purchaseValue: Double = 0.0,
    val marketValue: Double = 0.0,
)

/** Value of the /api/sets map, keyed by lowercase set code. */
@Serializable
data class SetDto(
    val name: String = "",
    val cardCount: Int = 0,
    val iconSvgUri: String = "",
    val releasedAt: String = "",
    val setType: String = "",
    val digital: Boolean = false,
)

@Serializable
data class SetCardDto(
    val collectorNumber: String,
    val name: String,
    val rarity: String = "",
    val typeLine: String = "",
    val priceEur: Double? = null,
    val priceEurFoil: Double? = null,
    /** Empty when the catalog has no image for this card. */
    val imageId: String = "",
    val hasBack: Boolean = false,
    val cardmarketUri: String = "",
    /** Absent when not owned. */
    val owned: SetCardOwnedDto? = null,
)

@Serializable
data class SetCardOwnedDto(
    val copies: Int = 0,
    val byLang: Map<String, Int> = emptyMap(),
    val byBinder: Map<String, Int> = emptyMap(),
)

@Serializable
data class PrintDto(
    val setCode: String,
    val setName: String = "",
    val iconSvgUri: String = "",
    val collectorNumber: String = "",
    val rarity: String = "",
    val imageId: String = "",
    val priceEur: Double? = null,
    val priceEurFoil: Double? = null,
)

@Serializable
data class BinderDto(
    val name: String,
    /** "binder" (Ordner) or "list" (Liste). */
    val type: String = "",
    val cards: Int = 0,
    val total: Int = 0,
    /** Server value; not foil-aware (main.go handleBinders), shown as is. */
    val marketValue: Double = 0.0,
)

@Serializable
data class EditionDto(
    val code: String,
    val name: String = "",
    val iconSvgUri: String = "",
    val setType: String = "",
    val releasedAt: String = "",
    val owned: Int = 0,
    /** Null when the set is unknown to the catalog. */
    val total: Int? = null,
    val missing: Int? = null,
    val dupeCount: Int = 0,
    val dupeExtra: Int = 0,
    val totalCopies: Int = 0,
    val marketValue: Double = 0.0,
    val inCollection: Boolean = false,
)

@Serializable
data class OwnedByNameRequest(val names: List<String>)

@Serializable
data class UiConfigDto(
    val randomEnabled: Boolean = false,
    val randomCount: Int = 6,
    /** Nav keys hidden by the admin, the web page names: "decks", "editions", ... */
    val hiddenNav: List<String> = emptyList(),
    val imageMirror: Boolean = false,
)

@Serializable
data class StatusDto(
    val running: Boolean = false,
    val lastError: String = "",
    val setsSyncedAt: String = "",
    val cardsSyncedAt: String = "",
    val collectionCount: Int = 0,
    val cardCount: Int = 0,
    val setCount: Int = 0,
    val remoteImport: RemoteImportStatusDto = RemoteImportStatusDto(),
    val backup: BackupStatusDto = BackupStatusDto(),
    val imageMirror: ImageMirrorStatusDto = ImageMirrorStatusDto(),
)

@Serializable
data class RemoteImportStatusDto(
    val enabled: Boolean = false,
    val lastAt: String = "",
    val lastSource: String = "",
    val lastError: String = "",
)

@Serializable
data class BackupStatusDto(
    val enabled: Boolean = false,
    val lastAt: String = "",
    val lastFile: String = "",
    val lastError: String = "",
    val lastRestoreAt: String = "",
)

@Serializable
data class ImageMirrorStatusDto(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val files: Int = 0,
    val bytes: Long = 0,
    val lastAt: String = "",
    val lastError: String = "",
)

@Serializable
data class WishlistDto(
    val summary: WishlistSummaryDto = WishlistSummaryDto(),
    val items: List<WishlistItemDto> = emptyList(),
)

@Serializable
data class WishlistSummaryDto(
    val items: Int = 0,
    val value: Double = 0.0,
    val targetsMet: Int = 0,
    val unpriced: Int = 0,
    val owned: Int = 0,
    val upgrades: Int = 0,
    val days: Int = 30,
)

/** wishlistItem in backend/wishlist.go. The name is the identity. */
@Serializable
data class WishlistItemDto(
    val name: String,
    val setCode: String = "",
    val collectorNumber: String = "",
    val targetEur: Double? = null,
    val minCondition: String = "",
    val note: String = "",
    val priority: Int = 0,
    val added: String = "",
    val imageId: String = "",
    val rarity: String = "",
    val cardmarketUri: String = "",
    val price: Double? = null,
    val priceSetCode: String = "",
    val priceSetName: String = "",
    val priceNumber: String = "",
    val pinned: Boolean = false,
    val unknownToCatalog: Boolean = false,
    val priceThen: Double? = null,
    val changePct: Double? = null,
    val targetMet: Boolean = false,
    val ownedQuantity: Int = 0,
    val bestCondition: String = "",
    val needsUpgrade: Boolean = false,
)

/** POST /api/wishlist upserts on the name; the whole row is sent every time. */
@Serializable
data class WishlistInputDto(
    val name: String,
    val setCode: String = "",
    val collectorNumber: String = "",
    val targetEur: Double? = null,
    val minCondition: String = "",
    val note: String = "",
    val priority: Int = 0,
)

/** /api/reserved (backend/reserved.go). Keyed by card name: any printing counts. */
@Serializable
data class ReservedDto(
    val summary: ReservedSummaryDto = ReservedSummaryDto(),
    val cards: List<ReservedCardDto> = emptyList(),
)

@Serializable
data class ReservedSummaryDto(
    val total: Int = 0,
    val owned: Int = 0,
    val missing: Int = 0,
    val ownedValue: Double = 0.0,
    val costToComplete: Double = 0.0,
    val missingUnpriced: Int = 0,
)

@Serializable
data class ReservedCardDto(
    val name: String,
    val owned: Boolean = false,
    val quantity: Int = 0,
    val ownedValue: Double = 0.0,
    /** Cheapest printing, null when none has a price. */
    val cheapestPrice: Double? = null,
    val imageId: String = "",
    val rarity: String = "",
    val cardmarketUri: String = "",
)
