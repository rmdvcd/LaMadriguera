package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetDraft
import com.lamadriguera.app.domain.model.BetEvent
import com.lamadriguera.app.domain.model.BetEvidenceImage
import com.lamadriguera.app.domain.model.BetOutcome
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.BettingGroup
import com.lamadriguera.app.domain.model.BettingGroupMember
import java.math.BigDecimal

/**
 * Flujos atómicos de apuestas peer-to-peer.
 *
 * Las operaciones que cambian el estado de una apuesta van por RPC confiables
 * para aplicar RLS, elegibilidad, límite diario y transiciones atómicas.
 */
interface BettingRepository {
    suspend fun getGroups(): Result<List<BettingGroup>>
    suspend fun getGroup(groupId: String): Result<BettingGroup?>
    suspend fun createGroup(name: String, topic: String?, visibility: String): Result<BettingGroup>
    suspend fun getMyMemberGroupIds(): Result<Set<String>>
    suspend fun getGroupMembers(groupId: String): Result<List<BettingGroupMember>>
    suspend fun joinPublicGroup(groupId: String): Result<BettingGroupMember>
    suspend fun inviteMemberByPhone(groupId: String, phone: String): Result<BettingGroupMember>
    suspend fun removeMember(groupId: String, userId: String): Result<Unit>

    /** Reglas visibles del grupo; solo el admin responsable (owner) las edita. */
    suspend fun setGroupRules(groupId: String, rules: String): Result<BettingGroup>

    suspend fun getGroupBets(groupId: String): Result<List<Bet>>
    /** Historial completo de apuestas en las que participa la sesión actual. */
    suspend fun getMyBets(): Result<List<Bet>>
    suspend fun getBet(betId: String): Result<Bet?>
    suspend fun getBetEvents(betId: String): Result<List<BetEvent>>
    suspend fun createBet(draft: BetDraft): Result<Bet>
    suspend fun acceptBet(betId: String): Result<Bet>
    suspend fun submitResult(
        betId: String,
        outcome: BetOutcome,
        evidenceText: String?,
        evidenceImage: BetEvidenceImage?
    ): Result<Bet>
    suspend fun openDispute(
        betId: String,
        evidenceText: String?,
        evidenceImage: BetEvidenceImage?
    ): Result<Bet>
    suspend fun resolveDispute(betId: String, outcome: BetOutcome, decisionNote: String): Result<Bet>
    suspend fun getEvidenceSignedUrl(evidenceRef: String): Result<String>

    suspend fun getControls(): Result<BettingControls>
    suspend fun updateDailyLimit(amountCup: BigDecimal): Result<BettingControls>
    suspend fun selfExcludeForThirtyDays(): Result<BettingControls>
}
