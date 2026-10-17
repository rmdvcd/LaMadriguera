package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Boost(
    val id: String,
    val listingId: String,
    val tier: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val paymentId: String
)
