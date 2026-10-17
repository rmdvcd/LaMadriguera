package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.data.remote.supabase.row.ListingRow
import java.time.Instant

fun ListingRow.toDomain(): Listing = Listing(
    id = id,
    ownerId = ownerId,
    kind = kind,
    title = title,
    description = description,
    categoryId = categoryId,
    price = price,
    currency = currency,
    priceNegotiable = priceNegotiable,
    municipalityId = municipalityId,
    radiusKm = radiusKm,
    privacyLevel = privacyLevel,
    saleMode = saleMode,
    auctionConfig = auctionConfig,
    status = status,
    boostedUntil = boostedUntil?.let(Instant::parse),
    createdAt = Instant.parse(createdAt),
    photoUrl = photoUrl,
    visibility = visibility
)

fun Listing.toRow(): ListingRow = ListingRow(
    id = id,
    ownerId = ownerId,
    kind = kind,
    title = title,
    description = description,
    categoryId = categoryId,
    price = price,
    currency = currency,
    priceNegotiable = priceNegotiable,
    municipalityId = municipalityId,
    radiusKm = radiusKm,
    privacyLevel = privacyLevel,
    saleMode = saleMode,
    auctionConfig = auctionConfig,
    status = status,
    boostedUntil = boostedUntil?.toString(),
    createdAt = createdAt.toString(),
    photoUrl = photoUrl,
    visibility = visibility
)
