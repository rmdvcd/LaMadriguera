package com.lamadriguera.app.domain.model

import java.time.Instant
import java.time.LocalDate

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class IdentityVerification(
    val id: String,
    val userId: String,
    val docType: String,
    val docRef: ByteArray,
    val fullName: String,
    val birthdate: LocalDate,
    val status: String = "pending",
    val verifiedBy: String? = null,
    val verifiedAt: Instant? = null,
    val retentionUntil: Instant
)
