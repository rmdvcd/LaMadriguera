package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.data.remote.supabase.row.BidRow
import java.time.Instant

fun BidRow.toDomain(): Bid = Bid(
    id = id,
    listingId = listingId,
    bidderId = bidderId,
    amount = amount,
    deviceHash = deviceHash,
    ipHash = ipHash,
    status = status,
    createdAt = Instant.parse(createdAt)
)

fun Bid.toRow(): BidRow = BidRow(
    id = id,
    listingId = listingId,
    bidderId = bidderId,
    amount = amount,
    deviceHash = deviceHash,
    ipHash = ipHash,
    status = status,
    createdAt = createdAt.toString()
)
