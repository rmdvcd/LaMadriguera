package com.lamadriguera.app.domain.model

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Registro inmutable (append-only) de acciones de moderación. Toda sanción o
 * bloqueo guarda aquí su evidencia; nunca se borra contenido sin dejar rastro.
 */
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
    val createdAt: Instant,
    val evidence: JsonObject? = null
)
