package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AiModerationResult
import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationCategory
import com.lamadriguera.app.domain.model.ModerationDecision
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.repository.ModerationApi
import com.lamadriguera.app.domain.repository.ModerationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Caso de uso central de moderación de texto. Se ejecuta ANTES de publicar un
 * listing, un mensaje en grupos públicos, una apuesta o un perfil.
 *
 * Capas, en orden:
 *  1. Reglas de dominio ([ModerationRules]): teléfonos en campos prohibidos,
 *     enlaces sospechosos, patrones de spam, apuestas ilegales → rechazo claro.
 *  2. IA en texto ([ModerationApi], OpenAI Moderation o Llama Guard, con la API
 *     configurable por variable de entorno). Categoría grave (violencia, acoso,
 *     contenido sexual, apuestas ilegales) → BLOQUEO + registro inmutable en
 *     `moderation_actions`.
 *  3. IA dudosa (umbral bajo) → se publica y entra SOLO ella a la cola humana.
 *
 * La IA no está disponible (error de red/servidor) ⇒ decisión dudosa: ante la
 * duda, revisión humana, nunca publicación silenciosa de contenido grave.
 */
@Singleton
class ModerateContentUseCase @Inject constructor(
    private val moderationApi: ModerationApi,
    private val moderationRepository: ModerationRepository
) {

    /** Modera el texto combinado del contenido y decide el desenlace. */
    suspend fun moderate(content: ModeratedContent): Result<ModerationDecision> {
        val text = content.combinedText()
        if (text.isBlank()) {
            return Result.Success(ModerationDecision.Allowed())
        }

        // 1. Reglas de dominio: determinísticas y siempre en cliente.
        val findings = ModerationRules.check(content)
        if (findings.isNotEmpty()) {
            val worst = findings.maxBy { it.severity }
            return Result.Success(
                ModerationDecision.Blocked(
                    category = worst.category,
                    severity = worst.severity,
                    reason = worst.message,
                    userMessage = worst.message
                )
            )
        }

        // 2. IA en texto.
        val ai = when (val apiResult = moderationApi.moderateText(text)) {
            is Result.Success -> apiResult.data
            is Result.Error -> {
                // Umbral bajo: sin veredicto de IA, un admin revisa.
                return Result.Success(
                    ModerationDecision.Doubtful(
                        category = ModerationCategory.OTHER,
                        severity = 1,
                        aiProvider = "unavailable",
                        excerpt = text.take(EXCERPT_LENGTH),
                        userMessage = "No pudimos verificar el contenido ahora; quedará sujeto a revisión."
                    )
                )
            }
        }

        val severe = ai.categories.filterValues { it > 0f }.keys
            .map { ModerationCategory.fromWire(it) }
            .filter { it.isSevere }

        // Categoría grave marcada por la IA ⇒ bloqueo siempre (requisito).
        if (severe.isNotEmpty() || (ai.flagged && ai.worstScore >= SEVERE_SCORE)) {
            val category = severe.maxByOrNull { severityOf(it) } ?: ai.worstCategory
            val severity = maxOf(severityOf(category), if (ai.worstScore >= SEVERE_SCORE) 4 else 3)
            val reason = "Bloqueado por moderación automática (${ai.provider}): ${category.wireValue}"
            val logged = when (
                val logResult = moderationRepository.recordAiBlock(
                    targetType = content.targetType,
                    targetId = UUID.randomUUID().toString(),
                    category = category.wireValue,
                    severity = severity,
                    reason = reason,
                    evidence = evidenceJson(content, ai)
                )
            ) {
                is Result.Success -> true
                is Result.Error -> false
            }
            return Result.Success(
                ModerationDecision.Blocked(
                    category = category,
                    severity = severity,
                    reason = reason,
                    userMessage = BLOCK_MESSAGES[category] ?: DEFAULT_BLOCK_MESSAGE,
                    logged = logged
                )
            )
        }

        // 3. Umbral bajo hacia la cola humana: cualquier señal dudosa.
        if (ai.flagged || ai.worstScore >= DOUBTFUL_SCORE) {
            return Result.Success(
                ModerationDecision.Doubtful(
                    category = ai.worstCategory,
                    severity = severityOf(ai.worstCategory).coerceIn(1, 3),
                    aiProvider = ai.provider,
                    aiScores = ai.scores,
                    excerpt = text.take(EXCERPT_LENGTH),
                    userMessage = "Tu publicación se ha enviado con revisión posterior por parte de un moderador."
                )
            )
        }

        return Result.Success(ModerationDecision.Allowed())
    }

    /**
     * Tras publicar contenido dudoso, lo registra en la cola humana con la
     * decisión de la IA. Solo lo dudoso llega a un admin.
     */
    suspend fun flagDoubtful(
        targetType: ModerationTargetType,
        targetId: String,
        decision: ModerationDecision.Doubtful,
        evidence: JsonObject? = null
    ): Result<Unit> {
        val flagged = moderationRepository.flagForReview(
            targetType = targetType,
            targetId = targetId,
            category = decision.category.wireValue,
            severity = decision.severity,
            excerpt = decision.excerpt,
            evidence = evidence,
            aiProvider = decision.aiProvider,
            aiScores = decision.aiScores.toJsonObject()
        )
        return when (flagged) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> flagged
        }
    }

    private fun evidenceJson(content: ModeratedContent, ai: AiModerationResult): JsonObject =
        buildJsonObject {
            put("text", content.combinedText())
            put("provider", ai.provider)
            put("scores", ai.scores.toJsonObject())
        }

    private fun Map<String, Float>.toJsonObject(): JsonObject = buildJsonObject {
        forEach { (key, value) -> put(key, value) }
    }

    companion object {
        /** "Umbral bajo": por encima de este score la IA ya manda a revisión humana. */
        const val DOUBTFUL_SCORE = 0.2f

        /** Por encima de este score se trata como categoría grave aunque no se señale. */
        const val SEVERE_SCORE = 0.85f

        const val EXCERPT_LENGTH = 280

        val DEFAULT_BLOCK_MESSAGE =
            "Este contenido no puede publicarse porque infringe las normas de La Madriguera."

        val BLOCK_MESSAGES: Map<ModerationCategory, String> = mapOf(
            ModerationCategory.VIOLENCE to "El contenido promueve violencia y no puede publicarse.",
            ModerationCategory.HARASSMENT to "El contenido contiene acoso u odio y no puede publicarse.",
            ModerationCategory.SEXUAL to "El contenido es sexualmente explícito y no puede publicarse.",
            ModerationCategory.ILLEGAL_GAMBLING to "El contenido promueve apuestas ilegales y no puede publicarse."
        )

        fun severityOf(category: ModerationCategory): Int = when (category) {
            ModerationCategory.SEXUAL -> 5
            ModerationCategory.VIOLENCE -> 5
            ModerationCategory.HARASSMENT -> 4
            ModerationCategory.ILLEGAL_GAMBLING -> 4
            ModerationCategory.SELF_HARM -> 3
            ModerationCategory.HATE -> 3
            ModerationCategory.PHONE_LEAK -> 3
            ModerationCategory.SUSPICIOUS_LINK -> 3
            ModerationCategory.SPAM -> 2
            ModerationCategory.OTHER -> 1
        }
    }
}
