package com.lamadriguera.app.domain.model

import java.math.BigDecimal
import java.time.Instant

/** Apuesta entre usuarios. La app registra acuerdos y evidencias; no mueve dinero. */
data class Bet(
    val id: String,
    val groupId: String,
    val creatorId: String,
    val acceptorId: String? = null,
    val description: String,
    val stakeAmount: BigDecimal,
    val stakeCurrency: String = "CUP",
    /** Cuota propuesta por el creador; nunca se fija ni calcula desde la plataforma. */
    val odds: BigDecimal? = null,
    val status: BetStatus = BetStatus.OPEN,
    val settledBy: String? = null,
    val settledAt: Instant? = null,
    val evidenceRef: String? = null,
    val evidenceText: String? = null,
    val acceptedAt: Instant? = null,
    /** Solo se establece cuando el administrador del grupo decide una disputa. */
    val decisionBy: String? = null,
    val decisionNote: String? = null,
    val createdAt: Instant,
    /** Ocultación por moderación: la fila permanece (nunca se borra) con evidencia. */
    val hiddenAt: Instant? = null,
    val hiddenBy: String? = null,
    val hiddenReason: String? = null
) {
    val isHidden: Boolean get() = hiddenAt != null
}

/** Una acción inmutable del historial de una apuesta. */
data class BetEvent(
    val id: String,
    val betId: String,
    val actorId: String,
    val eventType: String,
    val outcome: String? = null,
    val evidenceText: String? = null,
    val evidenceRef: String? = null,
    val decisionNote: String? = null,
    val createdAt: Instant
)

/** Datos de una captura que se subirá al bucket privado de evidencias. */
data class BetEvidenceImage(
    val bytes: ByteArray,
    val mimeType: String
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is BetEvidenceImage && bytes.contentEquals(other.bytes) && mimeType == other.mimeType)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

data class BettingControls(
    val userId: String,
    val dailyLimitCup: BigDecimal = BigDecimal("1000"),
    val selfExcludedUntil: Instant? = null,
    val updatedAt: Instant
)

enum class BetOutcome(val wireValue: String) {
    CREATOR("creator"),
    ACCEPTOR("acceptor"),
    VOID("void");

    companion object {
        fun fromWire(value: String?): BetOutcome? = entries.firstOrNull { it.wireValue == value }
    }
}

fun BetStatus.outcome(): BetOutcome? = when (this) {
    BetStatus.SETTLED_CREATOR -> BetOutcome.CREATOR
    BetStatus.SETTLED_ACCEPTOR -> BetOutcome.ACCEPTOR
    BetStatus.VOID -> BetOutcome.VOID
    else -> null
}

fun BetOutcome.toStatus(): BetStatus = when (this) {
    BetOutcome.CREATOR -> BetStatus.SETTLED_CREATOR
    BetOutcome.ACCEPTOR -> BetStatus.SETTLED_ACCEPTOR
    BetOutcome.VOID -> BetStatus.VOID
}

/** Lo que introduce una persona al crear una apuesta. */
data class BetDraft(
    val groupId: String,
    val description: String,
    val stakeAmount: BigDecimal,
    val odds: BigDecimal? = null
)
