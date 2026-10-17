package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class BettingGroup(
    val id: String,
    val ownerId: String,
    val name: String,
    val topic: String? = null,
    val visibility: String = "private",
    val status: String = "active",
    val createdAt: Instant
)
