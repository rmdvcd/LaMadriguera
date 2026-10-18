package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.model.Bet
import com.lamadriguera.app.domain.model.BetEvidenceImage
import com.lamadriguera.app.domain.model.BettingControls
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.model.IdentityVerificationStatus
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.model.UserStatus
import com.lamadriguera.app.domain.model.VerificationLevel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Reglas de negocio del módulo; la base de datos repite las reglas críticas. */
object BettingRules {
    val DEFAULT_DAILY_LIMIT_CUP: BigDecimal = BigDecimal("1000")
    val MIN_DAILY_LIMIT_CUP: BigDecimal = BigDecimal.ONE
    val MAX_DAILY_LIMIT_CUP: BigDecimal = BigDecimal("1000000")
    const val MAX_DESCRIPTION_LENGTH = 500
    const val MAX_EVIDENCE_TEXT_LENGTH = 2_000
    const val MAX_EVIDENCE_IMAGE_BYTES = 5 * 1024 * 1024
    const val EXCLUSION_DAYS = 30L

    const val PAYMENT_NOTICE = "el pago se acuerda y ejecuta fuera de la app"
    const val RISK_NOTICE =
        "Apostar implica riesgo de pérdida. Participa solo si puedes asumirlo. La app no mueve dinero ni garantiza el pago."

    /** La edad se calcula usando exclusivamente la fecha de nacimiento aprobada del carnet. */
    fun eligibility(
        user: User?,
        approvedCarnet: IdentityVerification?,
        controls: BettingControls?,
        now: Instant = Instant.now()
    ): BettingEligibility {
        if (user == null || user.status != UserStatus.ACTIVE) {
            return BettingEligibility(false, "Tu cuenta debe estar activa para apostar.")
        }
        if (user.verificationLevel != VerificationLevel.IDENTITY) {
            return BettingEligibility(false, "Para apostar necesitas verificación de identidad aprobada.")
        }
        if (approvedCarnet == null ||
            approvedCarnet.docType != IdentityVerificationRequestType.CARNET ||
            approvedCarnet.status != IdentityVerificationStatus.APPROVED ||
            approvedCarnet.verifiedBy == null ||
            approvedCarnet.verifiedAt == null
        ) {
            return BettingEligibility(false, "La edad debe estar validada en un carnet aprobado.")
        }
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (approvedCarnet.birthdate.plusYears(18).isAfter(today)) {
            return BettingEligibility(false, "Solo pueden apostar personas de 18 años o más.")
        }
        val excludedUntil = controls?.selfExcludedUntil
        if (excludedUntil != null && now.isBefore(excludedUntil)) {
            return BettingEligibility(
                false,
                "La autoexclusión está activa hasta ${excludedUntil.atZone(ZoneOffset.UTC).toLocalDate()}."
            )
        }
        return BettingEligibility(true, null)
    }

    fun validateGroup(name: String, topic: String?, visibility: String): String? = when {
        name.trim().isEmpty() -> "Escribe el nombre del grupo."
        name.trim().length > 80 -> "El nombre del grupo no puede superar 80 caracteres."
        topic.orEmpty().trim().length > 200 -> "El tema no puede superar 200 caracteres."
        visibility !in setOf("private", "public") -> "Selecciona una visibilidad válida."
        else -> null
    }

    fun validateBetDraft(
        groupId: String,
        description: String,
        stakeAmount: BigDecimal?,
        odds: BigDecimal?
    ): String? {
        if (groupId.isBlank()) return "Selecciona un grupo."
        if (description.trim().length < 5) return "La descripción debe tener al menos 5 caracteres."
        if (description.trim().length > MAX_DESCRIPTION_LENGTH) {
            return "La descripción no puede superar $MAX_DESCRIPTION_LENGTH caracteres."
        }
        if (stakeAmount == null || stakeAmount <= BigDecimal.ZERO) {
            return "El monto debe ser mayor que 0 CUP."
        }
        if (!hasAtMostScale(stakeAmount, 2)) return "El monto admite como máximo 2 decimales."
        if (odds != null && odds <= BigDecimal.ZERO) return "La cuota propuesta debe ser mayor que 0."
        if (odds != null && !hasAtMostScale(odds, 4)) {
            return "La cuota propuesta admite como máximo 4 decimales."
        }
        return null
    }

    fun validateDailyLimit(amount: BigDecimal?): String? = when {
        amount == null -> "Escribe un límite diario válido."
        amount < MIN_DAILY_LIMIT_CUP -> "El límite mínimo es 1 CUP."
        amount > MAX_DAILY_LIMIT_CUP -> "El límite máximo es 1 000 000 CUP."
        !hasAtMostScale(amount, 2) -> "El límite admite como máximo 2 decimales."
        else -> null
    }

    fun validateEvidence(text: String?, image: BetEvidenceImage?): String? {
        if (text.orEmpty().trim().length > MAX_EVIDENCE_TEXT_LENGTH) {
            return "La evidencia de texto no puede superar $MAX_EVIDENCE_TEXT_LENGTH caracteres."
        }
        val hasText = !text.isNullOrBlank()
        if (!hasText && image == null) return "Añade una explicación o una captura como evidencia."
        if (image != null) {
            if (image.bytes.isEmpty() || image.bytes.size > MAX_EVIDENCE_IMAGE_BYTES) {
                return "La captura debe pesar como máximo 5 MB."
            }
            val jpeg = image.bytes.size >= 3 &&
                image.bytes[0] == 0xFF.toByte() && image.bytes[1] == 0xD8.toByte() && image.bytes[2] == 0xFF.toByte()
            val png = image.bytes.size >= 4 &&
                image.bytes[0] == 0x89.toByte() && image.bytes[1] == 0x50.toByte() &&
                image.bytes[2] == 0x4E.toByte() && image.bytes[3] == 0x47.toByte()
            if ((image.mimeType == "image/jpeg" && !jpeg) || (image.mimeType == "image/png" && !png) ||
                image.mimeType !in setOf("image/jpeg", "image/png")
            ) {
                return "La captura debe ser una imagen JPEG o PNG válida."
            }
        }
        return null
    }

    /**
     * Uso diario en CUP (día UTC): propuestas creadas hoy y apuestas aceptadas
     * hoy. Las propuestas abiertas también cuentan hasta que se resuelvan.
     */
    fun dailyUsage(
        bets: List<Bet>,
        userId: String,
        now: Instant = Instant.now()
    ): BigDecimal {
        val start = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = start.plusSeconds(24 * 60 * 60)
        return bets.asSequence()
            .distinctBy { it.id }
            .filter { bet ->
                (bet.creatorId == userId && !bet.createdAt.isBefore(start) && bet.createdAt.isBefore(end)) ||
                    (bet.acceptorId == userId && bet.acceptedAt?.let { !it.isBefore(start) && it.isBefore(end) } == true)
            }
            .fold(BigDecimal.ZERO) { total, bet -> total + bet.stakeAmount }
    }

    fun remainingDailyLimit(controls: BettingControls?, usedToday: BigDecimal): BigDecimal =
        ((controls?.dailyLimitCup ?: DEFAULT_DAILY_LIMIT_CUP) - usedToday).max(BigDecimal.ZERO)

    fun fitsDailyLimit(
        controls: BettingControls?,
        usedToday: BigDecimal,
        proposedStake: BigDecimal
    ): Boolean = usedToday + proposedStake <= (controls?.dailyLimitCup ?: DEFAULT_DAILY_LIMIT_CUP)

    private fun hasAtMostScale(value: BigDecimal, maxScale: Int): Boolean = try {
        value.setScale(maxScale, RoundingMode.UNNECESSARY)
        true
    } catch (_: ArithmeticException) {
        false
    }

    private object IdentityVerificationRequestType {
        const val CARNET = "carnet"
    }
}

data class BettingEligibility(val canBet: Boolean, val reason: String?)
