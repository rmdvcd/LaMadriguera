package com.lamadriguera.app.domain.model

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Modelos de dominio del sistema de moderación de La Madriguera.
 *
 * Principios:
 *  - Una acción de moderación nunca borra el contenido original: se marca como
 *    oculto y se guarda evidencia.
 *  - Sanciones: warn, hide, suspend (30 días), ban. El bloqueo pre-publicación
 *    ('block') es un rechazo auditado, no una sanción.
 *  - La cola humana recibe solo contenido dudoso marcado por la IA (umbral
 *    bajo) y reportes de usuarios.
 *  - La plataforma recibe reportes pero no modera el chat privado.
 */
enum class ModerationTargetType(val wireValue: String) {
    LISTING("listing"),
    GROUP_MESSAGE("group_message"),
    BET("bet"),
    PROFILE("profile"),
    MESSAGE("message"),
    BETTING_GROUP("betting_group");

    companion object {
        fun fromWire(value: String): ModerationTargetType =
            entries.firstOrNull { it.wireValue == value } ?: LISTING
    }
}

enum class ModerationCategory(val wireValue: String) {
    VIOLENCE("violence"),
    HARASSMENT("harassment"),
    SEXUAL("sexual"),
    ILLEGAL_GAMBLING("illegal_gambling"),
    SELF_HARM("self_harm"),
    HATE("hate"),
    SPAM("spam"),
    PHONE_LEAK("phone_leak"),
    SUSPICIOUS_LINK("suspicious_link"),
    OTHER("other");

    /** Categorías graves: bloquean la publicación (requisito de producto). */
    val isSevere: Boolean
        get() = this == VIOLENCE || this == HARASSMENT || this == SEXUAL || this == ILLEGAL_GAMBLING

    companion object {
        fun fromWire(value: String): ModerationCategory =
            entries.firstOrNull { it.wireValue == value } ?: OTHER
    }
}

/** Sanciones aplicables sobre contenido/autores. */
enum class Sanction(val wireValue: String, val label: String) {
    WARN("warn", "Avisar"),
    HIDE("hide", "Ocultar"),
    SUSPEND("suspend", "Suspender 30 días"),
    BAN("ban", "Expulsar");

    companion object {
        fun fromWire(value: String): Sanction? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Estado de una revisión en la cola humana. */
enum class ModerationReviewStatus(val wireValue: String, val label: String) {
    PENDING("pending", "Pendiente"),
    IN_REVIEW("in_review", "En revisión"),
    RESOLVED("resolved", "Resuelta"),
    DISMISSED("dismissed", "Descartada");

    companion object {
        fun fromWire(value: String): ModerationReviewStatus =
            entries.firstOrNull { it.wireValue == value } ?: PENDING
    }
}

/** Resultado normalizado de la API de moderación de texto. */
data class AiModerationResult(
    val provider: String,
    val flagged: Boolean,
    /** categoría canónica → score 0..1 */
    val categories: Map<String, Float> = emptyMap(),
    /** scores crudos del proveedor (auditoría) */
    val scores: Map<String, Float> = emptyMap()
) {
    val worstCategory: ModerationCategory
        get() = categories.entries
            .filter { it.value > 0f }
            .maxByOrNull { it.value }
            ?.let { ModerationCategory.fromWire(it.key) }
            ?: ModerationCategory.OTHER

    val worstScore: Float get() = categories.values.maxOrNull() ?: 0f
}

/** Contenido textual sometido a moderación antes de publicar. */
data class ModeratedContent(
    val targetType: ModerationTargetType,
    val title: String? = null,
    val body: String? = null
) {
    /** Texto completo que ve la IA (título + cuerpo, sin duplicados). */
    fun combinedText(): String =
        listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, body?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString("\n")
}

/** Decisión de [com.lamadriguera.app.domain.usecase.ModerateContentUseCase]. */
sealed interface ModerationDecision {
    val category: ModerationCategory
    val severity: Int
    val userMessage: String

    /** Se publica sin más trámites. */
    data class Allowed(
        override val category: ModerationCategory = ModerationCategory.OTHER,
        override val severity: Int = 0,
        override val userMessage: String = ""
    ) : ModerationDecision

    /**
     * IA dudosa (umbral bajo): se publica y entra a la cola humana mediante
     * [ModerationRepository.flagForReview] con el id del contenido publicado.
     */
    data class Doubtful(
        override val category: ModerationCategory,
        override val severity: Int,
        val aiProvider: String,
        val aiScores: Map<String, Float> = emptyMap(),
        val excerpt: String = "",
        override val userMessage: String = "Tu publicación quedará sujeta a revisión."
    ) : ModerationDecision

    /** Bloqueado: NO se publica. La IA grave además queda registrada en moderation_actions. */
    data class Blocked(
        override val category: ModerationCategory,
        override val severity: Int,
        val reason: String,
        override val userMessage: String,
        val logged: Boolean = false
    ) : ModerationDecision
}

/** Fila de la cola humana (`moderation_reviews`). */
data class ModerationReview(
    val id: String,
    val targetType: ModerationTargetType,
    val targetId: String,
    val source: ModerationSource,
    val category: String,
    val severity: Int,
    val status: ModerationReviewStatus,
    val excerpt: String? = null,
    val evidence: JsonObject? = null,
    val aiProvider: String? = null,
    val aiScores: JsonObject? = null,
    val reportId: String? = null,
    val createdAt: Instant,
    val handledBy: String? = null,
    val handledAt: Instant? = null,
    val resolution: String? = null
)

/** Sanción que se aplicará desde la cola (admin de plataforma o de grupo). */
data class SanctionRequest(
    val targetType: ModerationTargetType,
    val targetId: String,
    val sanction: Sanction,
    val reason: String,
    val category: String = "other",
    val severity: Int = 3,
    val evidence: JsonObject? = null
)

/** Evidencia capturada al sancionar u ocultar (snapshot del contenido). */
data class ModerationEvidence(
    val excerpt: String? = null,
    val snapshot: JsonObject? = null
)
