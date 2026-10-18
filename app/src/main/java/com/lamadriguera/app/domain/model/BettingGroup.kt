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
    val createdAt: Instant,
    /** Reglas visibles del grupo; el admin responsable (owner) puede editarlas. */
    val rules: String = ""
)

/** Mensaje del muro de un grupo. Se publica tras pasar la moderación de texto. */
data class GroupMessage(
    val id: String,
    val groupId: String,
    val senderId: String,
    val body: String,
    val hiddenAt: Instant? = null,
    val hiddenBy: String? = null,
    val hiddenReason: String? = null,
    val createdAt: Instant
) {
    val isHidden: Boolean get() = hiddenAt != null
}
