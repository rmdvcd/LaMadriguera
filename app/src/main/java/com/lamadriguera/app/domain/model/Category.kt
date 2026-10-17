package com.lamadriguera.app.domain.model


/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Category(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val isCustom: Boolean = false,
    val createdBy: String? = null
)
