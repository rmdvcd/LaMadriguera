package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Boost
import com.lamadriguera.app.data.remote.supabase.row.BoostRow
import java.time.Instant

fun BoostRow.toDomain(): Boost = Boost(
    id = id,
    listingId = listingId,
    tier = tier,
    startsAt = Instant.parse(startsAt),
    endsAt = Instant.parse(endsAt),
    paymentId = paymentId
)

fun Boost.toRow(): BoostRow = BoostRow(
    id = id,
    listingId = listingId,
    tier = tier,
    startsAt = startsAt.toString(),
    endsAt = endsAt.toString(),
    paymentId = paymentId
)
