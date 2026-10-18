package com.lamadriguera.app.domain

import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetEvidenceImage
import com.lamadriguera.app.domain.model.BetStatus
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.IdentityVerificationStatus
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.model.UserStatus
import com.lamadriguera.app.domain.model.VerificationLevel
import com.lamadriguera.app.domain.usecase.BettingRules
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BettingRulesTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val user = User(
        id = "user-1",
        verificationLevel = VerificationLevel.IDENTITY,
        status = UserStatus.ACTIVE,
        createdAt = now.minusSeconds(86_400)
    )

    private fun carnet(birthdate: LocalDate = LocalDate.of(2008, 10, 18)) = IdentityVerification(
        id = "identity-1",
        userId = user.id,
        docType = "carnet",
        docRef = byteArrayOf(1),
        fullName = "Persona de Prueba",
        birthdate = birthdate,
        status = IdentityVerificationStatus.APPROVED,
        verifiedBy = "admin",
        verifiedAt = now.minusSeconds(86_400),
        retentionUntil = now.plusSeconds(86_400)
    )

    @Test
    fun onlyActiveIdentityVerifiedAdultsWithApprovedCarnetCanBet() {
        assertTrue(BettingRules.eligibility(user, carnet(), BettingControls(user.id, updatedAt = now), now).canBet)
        assertFalse(
            BettingRules.eligibility(user, carnet(LocalDate.of(2008, 10, 19)), BettingControls(user.id, updatedAt = now), now).canBet
        )
        assertFalse(
            BettingRules.eligibility(user, carnet().copy(status = IdentityVerificationStatus.PENDING), BettingControls(user.id, updatedAt = now), now).canBet
        )
        assertFalse(
            BettingRules.eligibility(user.copy(verificationLevel = VerificationLevel.PHONE), carnet(), BettingControls(user.id, updatedAt = now), now).canBet
        )
        assertFalse(
            BettingRules.eligibility(user.copy(status = UserStatus.SUSPENDED), carnet(), BettingControls(user.id, updatedAt = now), now).canBet
        )
    }

    @Test
    fun autoExclusionBlocksBettingUntilTheStoredDeadline() {
        val controls = BettingControls(
            userId = user.id,
            selfExcludedUntil = now.plusSeconds(1),
            updatedAt = now
        )
        val eligibility = BettingRules.eligibility(user, carnet(), controls, now)
        assertFalse(eligibility.canBet)
        assertTrue(eligibility.reason.orEmpty().contains("autoexclusión"))
        assertTrue(BettingRules.EXCLUSION_DAYS == 30L)
    }

    @Test
    fun aBetMayOmitOddsButAmountsAndProposedOddsMustBePositiveAndExact() {
        assertNull(BettingRules.validateBetDraft("group", "Equipo A gana", BigDecimal("10.25"), null))
        assertNull(BettingRules.validateBetDraft("group", "Equipo A gana", BigDecimal("10"), BigDecimal("1.875")))
        assertTrue(BettingRules.validateBetDraft("group", "x", BigDecimal("10"), null) != null)
        assertTrue(BettingRules.validateBetDraft("group", "Partido", BigDecimal.ZERO, null) != null)
        assertTrue(BettingRules.validateBetDraft("group", "Partido", BigDecimal("1.001"), null) != null)
        assertTrue(BettingRules.validateBetDraft("group", "Partido", BigDecimal("10"), BigDecimal.ZERO) != null)
        assertTrue(BettingRules.validateBetDraft("group", "Partido", BigDecimal("10"), BigDecimal("1.12345")) != null)
    }

    @Test
    fun dailyUseCountsProposalsCreatedTodayAndWagersAcceptedTodayInUtc() {
        val today = Bet(
            id = "today",
            groupId = "g",
            creatorId = user.id,
            description = "hoy",
            stakeAmount = BigDecimal("75"),
            createdAt = Instant.parse("2026-10-18T01:00:00Z")
        )
        val acceptedToday = Bet(
            id = "accepted",
            groupId = "g",
            creatorId = "someone",
            acceptorId = user.id,
            description = "aceptada hoy",
            stakeAmount = BigDecimal("25"),
            status = BetStatus.ACCEPTED,
            acceptedAt = Instant.parse("2026-10-18T23:00:00Z"),
            createdAt = Instant.parse("2026-10-17T23:59:00Z")
        )
        val yesterday = today.copy(
            id = "yesterday",
            stakeAmount = BigDecimal("900"),
            createdAt = Instant.parse("2026-10-17T23:59:59Z")
        )
        assertEquals(BigDecimal("100"), BettingRules.dailyUsage(listOf(today, acceptedToday, yesterday), user.id, now))
        assertEquals(BigDecimal("0"), BettingRules.remainingDailyLimit(null, BigDecimal("1000")))
        assertFalse(BettingRules.fitsDailyLimit(BettingControls(user.id, updatedAt = now), BigDecimal("999"), BigDecimal("2")))
    }

    @Test
    fun evidenceCanBeTextOrAValidScreenshotAndNeverEmpty() {
        assertTrue(BettingRules.validateEvidence(null, null) != null)
        assertNull(BettingRules.validateEvidence("Lo vi en el marcador final", null))
        assertNull(BettingRules.validateEvidence(null, BetEvidenceImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png")))
        assertTrue(BettingRules.validateEvidence(null, BetEvidenceImage(byteArrayOf(1), "image/webp")) != null)
        assertTrue(BettingRules.validateEvidence("x".repeat(2_001), null) != null)
    }

    @Test
    fun dailyLimitDefaultsToOneThousandCupAndHasSafeBounds() {
        assertEquals(BigDecimal("1000"), BettingRules.DEFAULT_DAILY_LIMIT_CUP)
        assertNull(BettingRules.validateDailyLimit(BigDecimal("15.50")))
        assertTrue(BettingRules.validateDailyLimit(BigDecimal.ZERO) != null)
        assertTrue(BettingRules.validateDailyLimit(BigDecimal("1000001")) != null)
    }
}
