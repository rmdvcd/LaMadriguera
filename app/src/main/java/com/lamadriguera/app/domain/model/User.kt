package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class User(
    val id: String,
    val phone: String? = null,
    val verificationLevel: VerificationLevel = VerificationLevel.NONE,
    val status: UserStatus = UserStatus.TEMP,
    val createdAt: Instant,
    /** Suspensión temporal de 30 días; vencida vuelve a 'active' (expire_user_suspensions). */
    val suspendedUntil: Instant? = null
)
