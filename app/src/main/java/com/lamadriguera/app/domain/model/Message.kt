package com.lamadriguera.app.domain.model

import java.time.Instant

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val type: MessageType = MessageType.TEXT,
    val contentRef: String,
    val expiresAt: Instant? = null,
    val reported: Boolean = false,
    val createdAt: Instant
)
