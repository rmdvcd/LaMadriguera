package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AiModerationResult

/**
 * Llamada a la API de moderación de texto (OpenAI Moderation o Llama Guard).
 * El endpoint y el proveedor son configurables por variable de entorno
 * (MODERATION_API_URL en el cliente; MODERATION_PROVIDER / MODERATION_API_URL /
 * MODERATION_API_KEY en la Edge Function `moderate-text`).
 */
interface ModerationApi {
    /** Clasifica el texto y devuelve categorías canónicas con sus scores. */
    suspend fun moderateText(text: String): Result<AiModerationResult>
}
