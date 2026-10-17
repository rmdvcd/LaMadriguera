package com.lamadriguera.app.domain.model


/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Conversation(
    val id: String,
    val listingId: String,
    val participantA: String,
    val participantB: String,
    val revealPolicy: RevealPolicy = RevealPolicy.NEVER,
    val sessionKeyWrapped: ByteArray? = null,
    val disputeKeySplitA: ByteArray? = null,
    val disputeKeySplitB: ByteArray? = null,
    val status: String = "active"
)
