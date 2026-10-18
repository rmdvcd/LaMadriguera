package com.lamadriguera.app.data.repository

import com.lamadriguera.app.core.result.AppError
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.SupabaseJson
import com.lamadriguera.app.data.remote.supabase.row.BetEventRow
import com.lamadriguera.app.data.remote.supabase.row.BetRow
import com.lamadriguera.app.data.remote.supabase.row.BettingControlsRow
import com.lamadriguera.app.data.remote.supabase.row.BettingGroupMemberRow
import com.lamadriguera.app.data.remote.supabase.row.BettingGroupRow
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetDraft
import com.lamadriguera.app.domain.model.BetEvent
import com.lamadriguera.app.domain.model.BetEvidenceImage
import com.lamadriguera.app.domain.model.BetOutcome
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.model.BettingGroupMember
import com.lamadriguera.app.domain.repository.BettingRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Supabase implementation. Every state transition goes through a database RPC;
 * the client never writes bet status, acceptor, settlement or decision columns.
 */
class SupabaseBettingRepository @Inject constructor(
    private val client: SupabaseClient
) : BettingRepository {

    override suspend fun getGroups(): Result<List<BettingGroup>> = request {
        fetchPages(BettingGroupRow.serializer(), "betting_groups") {
            order("created_at", Order.DESCENDING)
        }.map(BettingGroupRow::toDomain)
    }

    override suspend fun getGroup(groupId: String): Result<BettingGroup?> = request {
        require(groupId.isNotBlank()) { "Empty group id" }
        decodeList(
            BettingGroupRow.serializer(),
            client.from("betting_groups").select { filter { eq("id", groupId) } }.data
        ).singleOrNull()?.toDomain()
    }

    override suspend fun createGroup(
        name: String,
        topic: String?,
        visibility: String
    ): Result<BettingGroup> {
        val ownerId = currentUserId() ?: return Result.Error(AppError.Unauthorized())
        val group = BettingGroup(
            id = UUID.randomUUID().toString(),
            ownerId = ownerId,
            name = name.trim(),
            topic = topic?.trim()?.takeIf(String::isNotEmpty),
            visibility = visibility,
            status = "active",
            createdAt = Instant.now()
        )
        return request {
            val payload = buildJsonObject {
                put("id", group.id)
                put("owner_id", group.ownerId)
                put("name", group.name)
                put("topic", group.topic)
                put("visibility", group.visibility)
            }
            decodeList(
                BettingGroupRow.serializer(),
                client.from("betting_groups").insert(payload) { select() }.data
            ).single().toDomain()
        }
    }

    override suspend fun getMyMemberGroupIds(): Result<Set<String>> {
        val userId = currentUserId() ?: return Result.Error(AppError.Unauthorized())
        return request {
            fetchPages(BettingGroupMemberRow.serializer(), "betting_group_members") {
                filter { eq("user_id", userId) }
                order("group_id", Order.ASCENDING)
            }.mapTo(linkedSetOf()) { it.groupId }
        }
    }

    override suspend fun getGroupMembers(groupId: String): Result<List<BettingGroupMember>> = request {
        require(groupId.isNotBlank()) { "Empty group id" }
        fetchPages(BettingGroupMemberRow.serializer(), "betting_group_members") {
            filter { eq("group_id", groupId) }
            order("created_at", Order.ASCENDING)
        }.map(BettingGroupMemberRow::toDomain)
    }

    override suspend fun joinPublicGroup(groupId: String): Result<BettingGroupMember> {
        if (groupId.isBlank()) return Result.Error(AppError.Validation("Selecciona un grupo."))
        return request {
            val data = client.postgrest.rpc(
                "join_public_betting_group",
                buildJsonObject { put("p_group_id", groupId) }
            ).data
            SupabaseJson.decodeFromString(BettingGroupMemberRow.serializer(), data).toDomain()
        }
    }

    override suspend fun inviteMemberByPhone(groupId: String, phone: String): Result<BettingGroupMember> {
        if (groupId.isBlank() || phone.isBlank()) {
            return Result.Error(AppError.Validation("Introduce el teléfono del miembro."))
        }
        return request {
            val data = client.postgrest.rpc(
                "invite_betting_group_member",
                buildJsonObject {
                    put("p_group_id", groupId)
                    put("p_phone", phone.trim())
                }
            ).data
            SupabaseJson.decodeFromString(BettingGroupMemberRow.serializer(), data).toDomain()
        }
    }

    override suspend fun removeMember(groupId: String, userId: String): Result<Unit> {
        if (groupId.isBlank() || userId.isBlank()) {
            return Result.Error(AppError.Validation("Selecciona un miembro."))
        }
        return request {
            client.postgrest.rpc(
                "remove_betting_group_member",
                buildJsonObject {
                    put("p_group_id", groupId)
                    put("p_user_id", userId)
                }
            )
            Unit
        }
    }

    override suspend fun getGroupBets(groupId: String): Result<List<Bet>> = request {
        require(groupId.isNotBlank()) { "Empty group id" }
        fetchPages(BetRow.serializer(), "bets") {
            filter { eq("group_id", groupId) }
            order("created_at", Order.DESCENDING)
        }.map(BetRow::toDomain)
    }

    override suspend fun getMyBets(): Result<List<Bet>> {
        val userId = currentUserId() ?: return Result.Error(AppError.Unauthorized())
        return request {
            val created = fetchPages(BetRow.serializer(), "bets") {
                filter { eq("creator_id", userId) }
                order("created_at", Order.DESCENDING)
            }
            val accepted = fetchPages(BetRow.serializer(), "bets") {
                filter { eq("acceptor_id", userId) }
                order("created_at", Order.DESCENDING)
            }
            (created + accepted)
                .distinctBy(BetRow::id)
                .sortedByDescending { it.createdAt }
                .map(BetRow::toDomain)
        }
    }

    override suspend fun getBet(betId: String): Result<Bet?> = request {
        require(betId.isNotBlank()) { "Empty bet id" }
        decodeList(
            BetRow.serializer(),
            client.from("bets").select { filter { eq("id", betId) } }.data
        ).singleOrNull()?.toDomain()
    }

    override suspend fun getBetEvents(betId: String): Result<List<BetEvent>> = request {
        require(betId.isNotBlank()) { "Empty bet id" }
        fetchPages(BetEventRow.serializer(), "bet_events") {
            filter { eq("bet_id", betId) }
            order("created_at", Order.ASCENDING)
        }.map(BetEventRow::toDomain)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun createBet(draft: BetDraft): Result<Bet> {
        if (draft.groupId.isBlank() || draft.description.isBlank() || draft.stakeAmount <= BigDecimal.ZERO) {
            return Result.Error(AppError.Validation("Completa la descripción y un monto válido en CUP."))
        }
        return request {
            val params = buildJsonObject {
                put("p_group_id", draft.groupId)
                put("p_description", draft.description.trim())
                put("p_stake_amount", JsonUnquotedLiteral(draft.stakeAmount.toPlainString()))
                if (draft.odds != null) put("p_odds", JsonUnquotedLiteral(draft.odds.toPlainString()))
            }
            val data = client.postgrest.rpc("create_bet", params).data
            SupabaseJson.decodeFromString(BetRow.serializer(), data).toDomain()
        }
    }

    override suspend fun acceptBet(betId: String): Result<Bet> {
        if (betId.isBlank()) return Result.Error(AppError.Validation("Apuesta no válida."))
        return request {
            val data = client.postgrest.rpc(
                "accept_bet",
                buildJsonObject { put("p_bet_id", betId) }
            ).data
            SupabaseJson.decodeFromString(BetRow.serializer(), data).toDomain()
        }
    }

    override suspend fun submitResult(
        betId: String,
        outcome: BetOutcome,
        evidenceText: String?,
        evidenceImage: BetEvidenceImage?
    ): Result<Bet> = submitEvidenceAction(
        betId = betId,
        evidenceText = evidenceText,
        evidenceImage = evidenceImage
    ) { evidenceRef ->
        val data = client.postgrest.rpc(
            "submit_bet_result",
            buildJsonObject {
                put("p_bet_id", betId)
                put("p_outcome", outcome.wireValue)
                val cleanedText = evidenceText?.trim()?.takeIf(String::isNotEmpty)
                if (cleanedText == null) put("p_evidence_text", JsonNull) else put("p_evidence_text", cleanedText)
                if (evidenceRef == null) put("p_evidence_ref", JsonNull) else put("p_evidence_ref", evidenceRef)
            }
        ).data
        SupabaseJson.decodeFromString(BetRow.serializer(), data).toDomain()
    }

    override suspend fun openDispute(
        betId: String,
        evidenceText: String?,
        evidenceImage: BetEvidenceImage?
    ): Result<Bet> = submitEvidenceAction(
        betId = betId,
        evidenceText = evidenceText,
        evidenceImage = evidenceImage
    ) { evidenceRef ->
        val data = client.postgrest.rpc(
            "open_bet_dispute",
            buildJsonObject {
                put("p_bet_id", betId)
                val cleanedText = evidenceText?.trim()?.takeIf(String::isNotEmpty)
                if (cleanedText == null) put("p_evidence_text", JsonNull) else put("p_evidence_text", cleanedText)
                if (evidenceRef == null) put("p_evidence_ref", JsonNull) else put("p_evidence_ref", evidenceRef)
            }
        ).data
        SupabaseJson.decodeFromString(BetRow.serializer(), data).toDomain()
    }

    override suspend fun resolveDispute(
        betId: String,
        outcome: BetOutcome,
        decisionNote: String
    ): Result<Bet> {
        if (outcome == BetOutcome.VOID && decisionNote.isBlank()) {
            return Result.Error(AppError.Validation("Explica la decisión del grupo."))
        }
        if (betId.isBlank() || decisionNote.isBlank()) {
            return Result.Error(AppError.Validation("La decisión requiere una nota."))
        }
        return request {
            val data = client.postgrest.rpc(
                "resolve_bet_dispute",
                buildJsonObject {
                    put("p_bet_id", betId)
                    put("p_outcome", outcome.wireValue)
                    put("p_decision_note", decisionNote.trim())
                }
            ).data
            SupabaseJson.decodeFromString(BetRow.serializer(), data).toDomain()
        }
    }

    override suspend fun getEvidenceSignedUrl(evidenceRef: String): Result<String> = request {
        require(evidenceRef.isNotBlank()) { "Empty evidence path" }
        client.storage.from(EVIDENCE_BUCKET).createSignedUrl(evidenceRef, SIGNED_URL_LIFETIME)
    }

    override suspend fun getControls(): Result<BettingControls> {
        val userId = currentUserId() ?: return Result.Error(AppError.Unauthorized())
        return request {
            val row = decodeList(
                BettingControlsRow.serializer(),
                client.from("betting_controls").select { filter { eq("user_id", userId) } }.data
            ).singleOrNull() ?: BettingControlsRow(
                userId = userId,
                dailyLimitCup = BigDecimal("1000"),
                selfExcludedUntil = null,
                updatedAt = Instant.now().toString()
            )
            row.toDomain()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun updateDailyLimit(amountCup: BigDecimal): Result<BettingControls> {
        if (amountCup <= BigDecimal.ZERO) {
            return Result.Error(AppError.Validation("El límite diario debe ser mayor que 0 CUP."))
        }
        return request {
            val data = client.postgrest.rpc(
                "update_betting_daily_limit",
                buildJsonObject { put("p_daily_limit_cup", JsonUnquotedLiteral(amountCup.toPlainString())) }
            ).data
            SupabaseJson.decodeFromString(BettingControlsRow.serializer(), data).toDomain()
        }
    }

    override suspend fun selfExcludeForThirtyDays(): Result<BettingControls> = request {
        val data = client.postgrest.rpc("self_exclude_betting").data
        SupabaseJson.decodeFromString(BettingControlsRow.serializer(), data).toDomain()
    }

    private suspend fun submitEvidenceAction(
        betId: String,
        evidenceText: String?,
        evidenceImage: BetEvidenceImage?,
        action: suspend (evidenceRef: String?) -> Bet
    ): Result<Bet> {
        if (betId.isBlank()) return Result.Error(AppError.Validation("Apuesta no válida."))
        val currentBet = when (val result = getBet(betId)) {
            is Result.Error -> return result
            is Result.Success -> result.data ?: return Result.Error(AppError.NotFound())
        }
        val userId = currentUserId() ?: return Result.Error(AppError.Unauthorized())
        var uploadedRef: String? = null
        if (evidenceImage != null) {
            val format = ImageFormat.detect(evidenceImage.bytes)
                ?: return Result.Error(AppError.Validation("La captura debe ser JPEG o PNG."))
            if (evidenceImage.bytes.size > ImageFormat.MAX_IMAGE_BYTES) {
                return Result.Error(AppError.Validation("La captura debe pesar como máximo 5 MB."))
            }
            val path = "$userId/${currentBet.groupId}/${currentBet.id}/${UUID.randomUUID()}.${format.extension}"
            when (val uploaded = request {
                client.storage.from(EVIDENCE_BUCKET).upload(path, evidenceImage.bytes) {
                    contentType = ContentType.parse(format.mimeType)
                }
                path
            }) {
                is Result.Error -> return uploaded
                is Result.Success -> uploadedRef = uploaded.data
            }
        }

        // Capturas y eventos son evidencia de auditoría: el cliente no puede borrarlos.
        // Si la RPC falla después de subir, el archivo sin referencia lo depura un job confiable.
        return request { action(uploadedRef) }
    }

    private fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    private suspend fun <Row : Any> fetchPages(
        serializer: KSerializer<Row>,
        table: String,
        configure: SelectRequestBuilder.() -> Unit
    ): List<Row> {
        val rows = mutableListOf<Row>()
        var start = 0L
        while (true) {
            val raw = client.from(table).select {
                configure()
                range(start, start + PAGE_SIZE - 1)
            }.data
            val page = decodeList(serializer, raw)
            rows += page
            if (page.size < PAGE_SIZE) break
            start += PAGE_SIZE
        }
        return rows
    }

    private fun <Row : Any> decodeList(serializer: KSerializer<Row>, data: String): List<Row> =
        SupabaseJson.decodeFromString(ListSerializer(serializer), data)

    companion object {
        private const val EVIDENCE_BUCKET = "bet-evidence"
        private const val PAGE_SIZE = 1000L
        private val SIGNED_URL_LIFETIME = 5.minutes
    }
}
