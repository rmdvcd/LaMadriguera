package com.lamadriguera.app.data.remote.moderation

import com.lamadriguera.app.BuildConfig
import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.repository.request
import com.lamadriguera.app.domain.model.AiModerationResult
import com.lamadriguera.app.domain.repository.ModerationApi
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.request.contentType
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cliente de la API de moderación de texto.
 *
 * El endpoint es configurable por variable de entorno:
 *  - `MODERATION_API_URL` (local.properties / -PMODERATION_API_URL): URL completa
 *    del endpoint de moderación. Vacío ⇒ Edge Function `moderate-text` del
 *    proyecto Supabase, que a su vez aplica `MODERATION_PROVIDER`,
 *    `MODERATION_API_URL` y `MODERATION_API_KEY` en sus secretos.
 */
@Singleton
class RemoteModerationApi @Inject constructor(
    private val client: SupabaseClient,
    private val httpClient: HttpClient
) : ModerationApi {

    override suspend fun moderateText(text: String): Result<AiModerationResult> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return Result.Success(AiModerationResult(provider = "none", flagged = false))
        }
        val accessToken = client.auth.currentAccessTokenOrNull()
            ?: return Result.Error(AppError.Unauthorized())
        val endpoint = moderationEndpoint()

        return request {
            val response = httpClient.post(endpoint) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(ModerationApiRequest(text = trimmed.take(MAX_TEXT_LENGTH)))
            }
            val responseText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val message = runCatching {
                    Json.decodeFromString(ModerationApiError.serializer(), responseText).error
                }.getOrNull()
                throw AppError.Server(message ?: "No se pudo moderar el texto")
            }
            val payload = Json.decodeFromString(ModerationApiPayload.serializer(), responseText)
            AiModerationResult(
                provider = payload.provider,
                flagged = payload.flagged,
                categories = payload.categories,
                scores = payload.scores
            )
        }
    }

    private fun moderationEndpoint(): String {
        val configured = BuildConfig.MODERATION_API_URL.trim()
        if (configured.isNotEmpty()) return configured
        return "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/moderate-text"
    }

    @Serializable
    private data class ModerationApiRequest(val text: String)

    @Serializable
    private data class ModerationApiPayload(
        val provider: String = "unknown",
        val flagged: Boolean = false,
        val categories: Map<String, Float> = emptyMap(),
        val scores: Map<String, Float> = emptyMap()
    )

    @Serializable
    private data class ModerationApiError(val error: String? = null)

    companion object {
        private const val MAX_TEXT_LENGTH = 8000
    }
}
