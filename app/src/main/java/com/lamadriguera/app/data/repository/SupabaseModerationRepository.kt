package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.ModerationActionRow
import com.lamadriguera.app.data.remote.supabase.row.ModerationReviewRow
import com.lamadriguera.app.domain.model.ModerationAction
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.SanctionRequest
import com.lamadriguera.app.domain.repository.ModerationRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import javax.inject.Inject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Cola humana y sanciones sobre `moderation_reviews` / `moderation_actions`.
 * Toda escritura va por RPC de servidor; el log es append-only y las sanciones
 * nunca borran contenido (se marca oculto con evidencia).
 */
class SupabaseModerationRepository @Inject constructor(
    private val client: SupabaseClient
) : SupabaseTableRepository<ModerationReview, ModerationReviewRow>(
    client = client,
    table = "moderation_reviews",
    primaryKey = "id",
    serializer = ModerationReviewRow.serializer(),
    toDomain = { it.toDomain() },
    toRow = { it.toRow() },
    // Solo lectura desde el cliente: el trigger de reportes y las RPC escriben.
    insertColumns = emptySet(),
    updateColumns = emptySet()
), ModerationRepository {

    override suspend fun reviewQueue(
        status: ModerationReviewStatus?,
        minSeverity: Int
    ): Result<List<ModerationReview>> = request {
        require(minSeverity in 0..5) { "Severidad no válida" }
        decodeRows(
            ModerationReviewRow.serializer(),
            client.from(TABLE).select {
                filter {
                    gte("severity", minSeverity)
                    if (status != null) eq("status", status.wireValue)
                }
                order("created_at", Order.DESCENDING)
                range(0, 199)
            }.data
        ).map { it.toDomain() }
    }

    override suspend fun groupReviewQueue(groupId: String): Result<List<ModerationReview>> = request {
        require(groupId.isNotBlank()) { "Grupo no válido" }
        val data = client.postgrest.rpc("group_moderation_queue", buildJsonObject { put("p_group_id", groupId) }).data
        decodeRows(ModerationReviewRow.serializer(), data).map { it.toDomain() }
    }

    override suspend fun flagForReview(
        targetType: ModerationTargetType,
        targetId: String,
        category: String,
        severity: Int,
        excerpt: String,
        evidence: JsonObject?,
        aiProvider: String?,
        aiScores: JsonObject?
    ): Result<ModerationReview> = request {
        require(targetId.isNotBlank()) { "Objetivo no válido" }
        val data = client.postgrest.rpc(
            "flag_moderation_review",
            buildJsonObject {
                put("p_target_type", targetType.wireValue)
                put("p_target_id", targetId)
                put("p_category", category)
                put("p_severity", severity)
                put("p_excerpt", excerpt)
                if (evidence != null) put("p_evidence", evidence) else put("p_evidence", null as String?)
                put("p_ai_provider", aiProvider)
                if (aiScores != null) put("p_ai_scores", aiScores) else put("p_ai_scores", null as String?)
            }
        ).data
        decodeRows(ModerationReviewRow.serializer(), data).single().toDomain()
    }

    override suspend fun recordAiBlock(
        targetType: ModerationTargetType,
        targetId: String,
        category: String,
        severity: Int,
        reason: String,
        evidence: JsonObject?
    ): Result<ModerationAction> = request {
        require(targetId.isNotBlank()) { "Objetivo no válido" }
        val data = client.postgrest.rpc(
            "record_ai_moderation_action",
            buildJsonObject {
                put("p_target_type", targetType.wireValue)
                put("p_target_id", targetId)
                put("p_category", category)
                put("p_severity", severity)
                put("p_reason", reason)
                if (evidence != null) put("p_evidence", evidence) else put("p_evidence", null as String?)
            }
        ).data
        decodeRows(ModerationActionRow.serializer(), data).single().toDomain()
    }

    override suspend fun applySanction(request: SanctionRequest): Result<ModerationAction> = request {
        val data = client.postgrest.rpc(
            "apply_moderation_action",
            buildJsonObject {
                put("p_target_type", request.targetType.wireValue)
                put("p_target_id", request.targetId)
                put("p_action", request.sanction.wireValue)
                put("p_reason", request.reason)
                put("p_category", request.category)
                put("p_severity", request.severity)
                if (request.evidence != null) put("p_evidence", request.evidence) else put("p_evidence", null as String?)
            }
        ).data
        decodeRows(ModerationActionRow.serializer(), data).single().toDomain()
    }

    override suspend fun dismissReview(reviewId: String, reason: String): Result<ModerationReview> = request {
        require(reviewId.isNotBlank()) { "Revisión no válida" }
        val data = client.postgrest.rpc(
            "dismiss_moderation_review",
            buildJsonObject {
                put("p_review_id", reviewId)
                put("p_reason", reason)
            }
        ).data
        decodeRows(ModerationReviewRow.serializer(), data).single().toDomain()
    }

    private fun <Row : Any> decodeRows(serializer: KSerializer<Row>, data: String): List<Row> =
        if (data.isBlank()) emptyList()
        else {
            val element = SupabaseJson.parseToJsonElement(data)
            when (element) {
                is JsonArray -> SupabaseJson.decodeFromJsonElement(ListSerializer(serializer), element)
                else -> listOf(SupabaseJson.decodeFromJsonElement(serializer, element))
            }
        }

    private companion object {
        const val TABLE = "moderation_reviews"
    }
}
