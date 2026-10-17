package com.lamadriguera.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    val participantIds: List<String>,
    val lastMessage: Message? = null,
    val updatedAt: String? = null
)

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val createdAt: String,
    val isRead: Boolean = false
)
