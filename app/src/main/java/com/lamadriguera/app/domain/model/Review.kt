package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Review(
    val id: String,
    val reviewerId: String,
    val revieweeId: String,
    val conversationId: String,
    val rating: Short,
    val text: String? = null,
    val status: String = "published",
    val createdAt: Instant
)
