package com.lamadriguera.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val email: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val phone: String? = null,
    val isVerified: Boolean = false,
    val createdAt: String? = null
)
