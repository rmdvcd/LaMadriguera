package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Report(
    val id: String,
    val reporterId: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val evidenceUrl: String? = null,
    val status: String = "open",
    val handledBy: String? = null,
    val createdAt: Instant
)
