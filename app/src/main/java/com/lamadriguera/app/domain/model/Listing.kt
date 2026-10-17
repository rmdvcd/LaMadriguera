package com.lamadriguera.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Listing(
    val id: String,
    val ownerId: String,
    val title: String,
    val description: String,
    val price: Double,
    val currency: String = "CUP",
    val images: List<String> = emptyList(),
    val status: ListingStatus = ListingStatus.ACTIVE,
    val createdAt: String? = null
)

enum class ListingStatus { ACTIVE, SOLD, PAUSED, DELETED }
