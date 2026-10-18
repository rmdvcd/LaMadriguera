package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.ModerationAction
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.Sanction
import com.lamadriguera.app.domain.model.SanctionRequest

/**
 * Cola humana de moderación y sanciones.
 *
 * - Solo llega a la cola lo que la IA marca como dudoso (umbral bajo) y los
 *   reportes de usuarios.
 * - Las sanciones (warn, hide, suspend, ban) nunca borran contenido: se marca
 *   oculto y se guarda evidencia en `moderation_actions` (append-only).
 * - Los admins de grupo actúan solo sobre el contenido de SU grupo.
 * - El chat privado se reporta pero no se modera.
 */
interface ModerationRepository {

    /** Cola de la plataforma (moderadores), con filtros por severidad y estado. */
    suspend fun reviewQueue(
        status: ModerationReviewStatus? = null,
        minSeverity: Int = 0
    ): Result<List<ModerationReview>>

    /** Cola del grupo para su admin responsable. */
    suspend fun groupReviewQueue(groupId: String): Result<List<ModerationReview>>

    /** Registra contenido dudoso de la IA en la cola humana (autor del contenido). */
    suspend fun flagForReview(
        targetType: ModerationTargetType,
        targetId: String,
        category: String,
        severity: Int,
        excerpt: String,
        evidence: kotlinx.serialization.json.JsonObject? = null,
        aiProvider: String? = null,
        aiScores: kotlinx.serialization.json.JsonObject? = null
    ): Result<ModerationReview>

    /**
     * Registra en `moderation_actions` un bloqueo de IA antes de publicar
     * (acción 'block', evidencia = texto intentado). No hay contenido previo.
     */
    suspend fun recordAiBlock(
        targetType: ModerationTargetType,
        targetId: String,
        category: String,
        severity: Int,
        reason: String,
        evidence: kotlinx.serialization.json.JsonObject? = null
    ): Result<ModerationAction>

    /** Aplica una sanción: warn | hide | suspend (30 días) | ban, con evidencia. */
    suspend fun applySanction(request: SanctionRequest): Result<ModerationAction>

    /** Descarta una revisión sin sanción (queda registro en la propia revisión). */
    suspend fun dismissReview(reviewId: String, reason: String): Result<ModerationReview>
}
