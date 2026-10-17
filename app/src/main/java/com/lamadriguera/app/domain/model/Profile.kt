package com.lamadriguera.app.domain.model

import java.math.BigDecimal

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Profile(
    val userId: String,
    val displayName: String,
    val alias: String? = null,
    val avatarUrl: String? = null,
    val municipalityId: Int? = null,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    val bio: String? = null,
    val reputationAvg: BigDecimal = BigDecimal.ZERO,
    val reviewsCount: Int = 0
)
