package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class ModerationAction(
    val id: String,
    val targetType: String,
    val targetId: String,
    val source: ModerationSource,
    val category: String,
    val severity: Short,
    val action: String,
    val reason: String,
    val actorId: String? = null,
    val createdAt: Instant
)
