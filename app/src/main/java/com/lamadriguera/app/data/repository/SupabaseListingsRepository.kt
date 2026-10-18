package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.BidRow
import com.lamadriguera.app.data.remote.supabase.row.BoostRow
import com.lamadriguera.app.data.remote.supabase.row.CategoryRow
import com.lamadriguera.app.data.remote.supabase.row.ListingRow
import com.lamadriguera.app.data.remote.supabase.row.ProfileRow
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.AuctionState
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingCandidate
import com.lamadriguera.app.domain.model.ListingDraft
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingSearchFilter
import com.lamadriguera.app.domain.model.ListingStatus
import com.lamadriguera.app.domain.model.ListingVisibility
import com.lamadriguera.app.domain.model.RankedListing
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import com.lamadriguera.app.domain.usecase.AuctionStateMachine
import com.lamadriguera.app.domain.usecase.ImageCompressor
import com.lamadriguera.app.domain.usecase.ListingRanker
import com.lamadriguera.app.domain.usecase.ListingValidator
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray

class SupabaseListingsRepository @Inject constructor(
    private val client: SupabaseClient,
    private val listingRanker: ListingRanker,
    private val imageCompressor: ImageCompressor
) : SupabaseTableRepository<Listing, ListingRow>(
        client = client,
        table = TABLE,
        primaryKey = "id",
        serializer = ListingRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "owner_id", "kind", "title", "description", "category_id", "price", "currency",
            "price_negotiable", "municipality_id", "radius_km", "privacy_level", "sale_mode",
            "auction_config", "status", "photo_url", "visibility"
        ),
        updateColumns = setOf(
            "kind", "title", "description", "category_id", "price", "currency", "price_negotiable",
            "municipality_id", "radius_km", "privacy_level", "sale_mode", "auction_config",
            "status", "photo_url", "visibility"
        )
    ), ListingsRepository {

    override suspend fun hasPublished(ownerId: String): Result<Boolean> = request {
        require(ownerId.isNotBlank()) { "Empty owner" }
        val data = client.from(TABLE).select {
            filter { eq("owner_id", ownerId) }
            range(0, 0)
        }.data
        SupabaseJson.parseToJsonElement(data).jsonArray.isNotEmpty()
    }

    override suspend fun uploadPhoto(bytes: ByteArray): Result<String> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        val compressed = when (val result = imageCompressor.compressForListing(bytes)) {
            is Result.Success -> result.data
            is Result.Error -> return result
        }
        val format = ImageFormat.detect(compressed)
            ?: return Result.Error(AppError.Validation(ListingValidator.MSG_PHOTO_FORMAT))
        if (compressed.size > ListingValidator.MAX_PHOTO_BYTES) {
            return Result.Error(AppError.Validation(ListingValidator.MSG_PHOTO_TOO_LARGE))
        }
        val path = "$userId/${UUID.randomUUID()}.${format.extension}"
        val bucket = client.storage.from(PHOTOS_BUCKET)
        return request {
            bucket.upload(path, compressed) {
                contentType = ContentType.parse(format.mimeType)
            }
            bucket.publicUrl(path)
        }
    }

    override suspend fun createFromDraft(draft: ListingDraft): Result<Listing> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())

        val compressedPhoto = if (draft.photoBytes != null) {
            when (val res = imageCompressor.compressForListing(draft.photoBytes)) {
                is Result.Success -> res.data
                is Result.Error -> return res
            }
        } else {
            null
        }
        val normalizedDraft = draft.copy(
            photoBytes = compressedPhoto,
            visibility = if (draft.kind == ListingKind.OFFER) {
                ListingVisibility.PUBLIC
            } else {
                draft.visibility
            }
        )
        ListingValidator.validate(normalizedDraft)?.let { message ->
            return Result.Error(AppError.Validation(message))
        }

        val photoUrl = if (compressedPhoto != null) {
            when (val upload = uploadPhoto(compressedPhoto)) {
                is Result.Success -> upload.data
                is Result.Error -> return upload
            }
        } else {
            normalizedDraft.existingPhotoUrl
        }

        val now = Instant.now()
        val saleMode = if (normalizedDraft.allowAuction) SaleMode.AUCTION else SaleMode.FIXED
        val auctionConfig = if (normalizedDraft.allowAuction) normalizedDraft.auctionConfig else null
        val effectivePrice = when {
            auctionConfig != null -> auctionConfig.startingPrice
            normalizedDraft.priceNegotiable -> normalizedDraft.price.coerceAtLeast(BigDecimal.ZERO)
            else -> normalizedDraft.price
        }
        val initialStatus = if (auctionConfig != null) {
            if (now.isBefore(auctionConfig.startsAt)) {
                ListingStatus.AUCTION_SCHEDULED
            } else {
                ListingStatus.AUCTION_LIVE
            }
        } else {
            ListingStatus.ACTIVE
        }

        val listing = Listing(
            id = UUID.randomUUID().toString(),
            ownerId = userId,
            kind = normalizedDraft.kind,
            title = normalizedDraft.title.trim(),
            description = normalizedDraft.description.trim(),
            categoryId = normalizedDraft.categoryId,
            price = effectivePrice,
            currency = normalizedDraft.currency,
            priceNegotiable = if (normalizedDraft.allowAuction) false else normalizedDraft.priceNegotiable,
            municipalityId = normalizedDraft.municipalityId,
            radiusKm = normalizedDraft.radiusKm,
            privacyLevel = normalizedDraft.privacyLevel,
            saleMode = saleMode,
            auctionConfig = auctionConfig?.toJsonObject(),
            status = initialStatus,
            createdAt = now,
            photoUrl = photoUrl,
            visibility = normalizedDraft.visibility
        )
        return create(listing)
    }

    override suspend fun updateFromDraft(listingId: String, draft: ListingDraft): Result<Listing> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        val existing = when (val found = find(listingId)) {
            is Result.Success -> found.data ?: return Result.Error(AppError.NotFound())
            is Result.Error -> return found
        }
        if (existing.ownerId != userId) {
            return Result.Error(AppError.Unauthorized(AuctionConfigValidator.MSG_ONLY_OWNER))
        }

        val compressedPhoto = if (draft.photoBytes != null) {
            when (val res = imageCompressor.compressForListing(draft.photoBytes)) {
                is Result.Success -> res.data
                is Result.Error -> return res
            }
        } else {
            null
        }
        val normalizedDraft = draft.copy(
            photoBytes = compressedPhoto,
            visibility = if (draft.kind == ListingKind.OFFER) {
                ListingVisibility.PUBLIC
            } else {
                draft.visibility
            }
        )
        ListingValidator.validate(normalizedDraft)?.let { message ->
            return Result.Error(AppError.Validation(message))
        }

        val newSaleMode = if (normalizedDraft.allowAuction) SaleMode.AUCTION else SaleMode.FIXED
        val newConfigJson = if (normalizedDraft.allowAuction) {
            normalizedDraft.auctionConfig?.toJsonObject()
        } else {
            null
        }

        if (existing.saleMode != newSaleMode || existing.auctionConfig != newConfigJson) {
            val bidsCount = countListingBids(listingId)
            AuctionConfigValidator.validateCanEdit(existing, userId, bidsCount)?.let { err ->
                return Result.Error(AppError.Validation(err))
            }
        }

        val photoUrl = if (compressedPhoto != null) {
            when (val upload = uploadPhoto(compressedPhoto)) {
                is Result.Success -> upload.data
                is Result.Error -> return upload
            }
        } else {
            normalizedDraft.existingPhotoUrl ?: existing.photoUrl
        }

        val now = Instant.now()
        val effectivePrice = when {
            normalizedDraft.allowAuction && normalizedDraft.auctionConfig != null ->
                normalizedDraft.auctionConfig.startingPrice
            normalizedDraft.priceNegotiable -> normalizedDraft.price.coerceAtLeast(BigDecimal.ZERO)
            else -> normalizedDraft.price
        }
        val nextStatus = if (normalizedDraft.allowAuction && normalizedDraft.auctionConfig != null) {
            if (now.isBefore(normalizedDraft.auctionConfig.startsAt)) {
                ListingStatus.AUCTION_SCHEDULED
            } else {
                ListingStatus.AUCTION_LIVE
            }
        } else {
            ListingStatus.ACTIVE
        }

        val updated = existing.copy(
            kind = normalizedDraft.kind,
            title = normalizedDraft.title.trim(),
            description = normalizedDraft.description.trim(),
            categoryId = normalizedDraft.categoryId,
            price = effectivePrice,
            currency = normalizedDraft.currency,
            priceNegotiable = if (normalizedDraft.allowAuction) false else normalizedDraft.priceNegotiable,
            municipalityId = normalizedDraft.municipalityId,
            radiusKm = normalizedDraft.radiusKm,
            privacyLevel = normalizedDraft.privacyLevel,
            saleMode = newSaleMode,
            auctionConfig = newConfigJson,
            status = nextStatus,
            photoUrl = photoUrl,
            visibility = normalizedDraft.visibility
        )
        return update(listingId, updated)
    }

    override suspend fun updateAuctionConfig(
        listingId: String,
        config: AuctionConfig?
    ): Result<Listing> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return Result.Error(AppError.Unauthorized())
        val existing = when (val found = find(listingId)) {
            is Result.Success -> found.data ?: return Result.Error(AppError.NotFound())
            is Result.Error -> return found
        }
        val bidsCount = countListingBids(listingId)
        AuctionConfigValidator.validateCanEdit(existing, userId, bidsCount)?.let { err ->
            return Result.Error(AppError.Validation(err))
        }
        if (config != null) {
            AuctionConfigValidator.validate(config)?.let { err ->
                return Result.Error(AppError.Validation(err))
            }
        }
        val now = Instant.now()
        val updated = if (config != null) {
            val status = if (now.isBefore(config.startsAt)) {
                ListingStatus.AUCTION_SCHEDULED
            } else {
                ListingStatus.AUCTION_LIVE
            }
            existing.copy(
                saleMode = SaleMode.AUCTION,
                auctionConfig = config.toJsonObject(),
                price = config.startingPrice,
                priceNegotiable = false,
                status = status
            )
        } else {
            existing.copy(
                saleMode = SaleMode.FIXED,
                auctionConfig = null,
                status = ListingStatus.FIXED
            )
        }
        return update(listingId, updated)
    }

    override suspend fun searchAndRank(
        filter: ListingSearchFilter,
        referenceMunicipalityId: Int?
    ): Result<List<RankedListing>> = request {
        val listingsData = client.from(TABLE).select {
            order("created_at", Order.DESCENDING)
            range(0, 99)
        }.data
        val listings = SupabaseJson.decodeFromString(
            ListSerializer(ListingRow.serializer()),
            listingsData
        ).map { it.toDomain() }

        val profilesMap = runCatching {
            val profilesData = client.from("profiles").select { range(0, 199) }.data
            SupabaseJson.decodeFromString(
                ListSerializer(ProfileRow.serializer()),
                profilesData
            ).associate { it.userId to it.toDomain() }
        }.getOrDefault(emptyMap())

        val categoriesMap = runCatching {
            val categoriesData = client.from("categories").select { range(0, 199) }.data
            SupabaseJson.decodeFromString(
                ListSerializer(CategoryRow.serializer()),
                categoriesData
            ).associate { it.id to it.name }
        }.getOrDefault(emptyMap())

        val now = Instant.now()
        val promotionMap = runCatching {
            val listingIds = listings.map { it.id }
            if (listingIds.isEmpty()) emptyMap() else {
                val promotionsData = client.from("boosts").select {
                    filter { isIn("listing_id", listingIds) }
                    order("starts_at", Order.DESCENDING)
                }.data
                SupabaseJson.decodeFromString(ListSerializer(BoostRow.serializer()), promotionsData)
                    .map { it.toDomain() }
                    .filter { it.startsAt <= now && it.endsAt > now }
                    .groupBy { it.listingId }
            }
        }.getOrDefault(emptyMap())

        val candidates = listings
            .filter { it.status != ListingStatus.CLOSED && it.status != ListingStatus.PAUSED_PAYMENT }
            .map { listing ->
                val ownerProfile = profilesMap[listing.ownerId]
                val displayName = when {
                    listing.privacyLevel == com.lamadriguera.app.domain.model.PrivacyLevel.ANON ->
                        ownerProfile?.alias?.let { "@$it" } ?: "Anónimo"
                    else -> ownerProfile?.displayName ?: "Usuario"
                }
                ListingCandidate(
                    listing = listing,
                    ownerReputationAvg = ownerProfile?.reputationAvg ?: BigDecimal.ZERO,
                    ownerReviewsCount = ownerProfile?.reviewsCount ?: 0,
                    ownerDisplayName = displayName,
                    categoryName = categoriesMap[listing.categoryId].orEmpty(),
                    responseRate = 0.8,
                    activePromotions = promotionMap[listing.id].orEmpty()
                )
            }

        listingRanker.filterAndRank(
            candidates = candidates,
            filter = filter,
            referenceMunicipalityId = referenceMunicipalityId ?: filter.municipalityId
        )
    }

    private suspend fun countListingBids(listingId: String): Int = runCatching {
        val raw = client.from("bids").select {
            filter { eq("listing_id", listingId) }
        }.data
        SupabaseJson.decodeFromString(ListSerializer(BidRow.serializer()), raw).size
    }.getOrDefault(0)

    companion object {
        private const val TABLE = "listings"
        const val PHOTOS_BUCKET = "listing-photos"
    }
}
