package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.BoostRow
import com.lamadriguera.app.domain.model.Boost
import com.lamadriguera.app.domain.model.PromotionScope
import java.time.Instant

fun BoostRow.toDomain(): Boost = Boost(
    id = id,
    listingId = listingId,
    tier = tier,
    startsAt = Instant.parse(startsAt),
    endsAt = Instant.parse(endsAt),
    paymentId = paymentId,
    productType = productType,
    reachScope = reachScope?.let { runCatching { PromotionScope.valueOf(it.uppercase()) }.getOrNull() }
)

fun Boost.toRow(): BoostRow = BoostRow(
    id = id,
    listingId = listingId,
    tier = tier,
    startsAt = startsAt.toString(),
    endsAt = endsAt.toString(),
    paymentId = paymentId,
    productType = productType,
    reachScope = reachScope?.name?.lowercase()
)
