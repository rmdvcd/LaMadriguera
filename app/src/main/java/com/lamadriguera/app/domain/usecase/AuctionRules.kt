package com.lamadriguera.app.domain.usecase

import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.AuctionState
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.model.BidStatus
import com.lamadriguera.app.domain.model.BidVisibility
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingStatus
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.model.UserStatus
import com.lamadriguera.app.domain.model.VerificationLevel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Validaciones de configuración de subastas (`AuctionConfigScreen` y dominio).
 */
object AuctionConfigValidator {

    const val MSG_STARTING_PRICE = "El precio inicial debe ser mayor que 0"
    const val MSG_MIN_INCREMENT = "El incremento mínimo debe ser mayor que 0"
    const val MSG_ENDS_AFTER_STARTS = "La fecha de cierre debe ser posterior a la de inicio"
    const val MSG_RESERVE_PRICE = "El precio de reserva debe ser mayor o igual al precio inicial"
    const val MSG_AUTO_EXTEND = "Los minutos de auto-extensión no pueden ser negativos"
    const val MSG_REQUIRES_IDENTITY = "Las subastas con dinero requieren verificación de identidad"
    const val MSG_ONLY_OWNER = "Solo el dueño puede editar la configuración de la subasta"
    const val MSG_HAS_BIDS = "No se puede editar la configuración cuando ya existen pujas"

    fun validate(config: AuctionConfig): String? {
        if (config.startingPrice <= BigDecimal.ZERO) return MSG_STARTING_PRICE
        if (config.minIncrement <= BigDecimal.ZERO) return MSG_MIN_INCREMENT
        if (!config.endsAt.isAfter(config.startsAt)) return MSG_ENDS_AFTER_STARTS
        if (config.reservePrice != null && config.reservePrice < config.startingPrice) {
            return MSG_RESERVE_PRICE
        }
        if (config.autoExtendMinutes < 0) return MSG_AUTO_EXTEND
        return null
    }

    /**
     * Solo el owner puede editar la config mientras no haya pujas.
     */
    fun validateCanEdit(listing: Listing, currentUserId: String?, bidsCount: Int): String? {
        if (currentUserId.isNullOrBlank() || listing.ownerId != currentUserId) {
            return MSG_ONLY_OWNER
        }
        if (bidsCount > 0) {
            return MSG_HAS_BIDS
        }
        return null
    }

    fun canEdit(listing: Listing, currentUserId: String?, bidsCount: Int): Boolean =
        validateCanEdit(listing, currentUserId, bidsCount) == null

    /**
     * Verifica si el historial de pujas (`BidHistoryScreen`) es visible públicamente.
     */
    fun canShowBidHistory(config: AuctionConfig?): Boolean =
        config?.visibleBids == BidVisibility.ALL
}

/**
 * Resultado de la resolución/cierre de una subasta.
 */
data class AuctionClosureOutcome(
    val transitionHistory: List<AuctionState>,
    val finalState: AuctionState,
    val updatedListing: Listing,
    val winningBid: Bid?,
    val updatedBids: List<Bid>
)

/**
 * Máquina de estados y lógica de cierre de subastas.
 *
 * Flujo de estados:
 * fixed -> auction_scheduled -> auction_live -> auction_ended ->
 * awarded | no_reserve_met | expired
 */
object AuctionStateMachine {

    private val allowedTransitions: Map<AuctionState, Set<AuctionState>> = mapOf(
        AuctionState.FIXED to setOf(AuctionState.AUCTION_SCHEDULED, AuctionState.AUCTION_LIVE),
        AuctionState.AUCTION_SCHEDULED to setOf(AuctionState.AUCTION_LIVE, AuctionState.FIXED),
        AuctionState.AUCTION_LIVE to setOf(AuctionState.AUCTION_ENDED),
        AuctionState.AUCTION_ENDED to setOf(
            AuctionState.AWARDED,
            AuctionState.NO_RESERVE_MET,
            AuctionState.EXPIRED
        ),
        AuctionState.AWARDED to emptySet(),
        AuctionState.NO_RESERVE_MET to emptySet(),
        AuctionState.EXPIRED to emptySet()
    )

    fun canTransition(from: AuctionState, to: AuctionState): Boolean =
        allowedTransitions[from]?.contains(to) == true

    fun resolveState(listing: Listing, now: Instant = Instant.now()): AuctionState {
        if (listing.saleMode == SaleMode.FIXED) return AuctionState.FIXED
        return when (listing.status) {
            ListingStatus.AWARDED -> AuctionState.AWARDED
            ListingStatus.NO_RESERVE_MET -> AuctionState.NO_RESERVE_MET
            ListingStatus.EXPIRED -> AuctionState.EXPIRED
            ListingStatus.AUCTION_ENDED -> AuctionState.AUCTION_ENDED
            else -> {
                val config = listing.parsedAuctionConfig ?: return AuctionState.FIXED
                when {
                    now.isBefore(config.startsAt) -> AuctionState.AUCTION_SCHEDULED
                    now.isBefore(config.endsAt) -> AuctionState.AUCTION_LIVE
                    else -> AuctionState.AUCTION_ENDED
                }
            }
        }
    }

    fun toListingStatus(state: AuctionState): ListingStatus = when (state) {
        AuctionState.FIXED -> ListingStatus.FIXED
        AuctionState.AUCTION_SCHEDULED -> ListingStatus.AUCTION_SCHEDULED
        AuctionState.AUCTION_LIVE -> ListingStatus.AUCTION_LIVE
        AuctionState.AUCTION_ENDED -> ListingStatus.AUCTION_ENDED
        AuctionState.AWARDED -> ListingStatus.AWARDED
        AuctionState.NO_RESERVE_MET -> ListingStatus.NO_RESERVE_MET
        AuctionState.EXPIRED -> ListingStatus.EXPIRED
    }

    /**
     * Auto-extiende `ends_at` si la puja entra dentro de la ventana `auto_extend_minutes`
     * previa al cierre.
     */
    fun computeAutoExtendedEndsAt(config: AuctionConfig, bidTime: Instant): Instant {
        if (config.autoExtendMinutes <= 0) return config.endsAt
        val remaining = Duration.between(bidTime, config.endsAt)
        val window = Duration.ofMinutes(config.autoExtendMinutes.toLong())
        return if (!remaining.isNegative && remaining <= window) {
            config.endsAt.plus(config.autoExtendMinutes.toLong(), ChronoUnit.MINUTES)
        } else {
            config.endsAt
        }
    }

    /**
     * Cierra una subasta que ha llegado a su fin (`now >= ends_at`):
     * - Si hay pujas válidas y se alcanzó `reserve_price` (o no se definió `reserve_price`),
     *   gana la puja mayor (`AWARDED`).
     * - Si hay pujas válidas pero la mayor es menor que `reserve_price`,
     *   no adjudica (`NO_RESERVE_MET`).
     * - Si no hay pujas válidas, queda desierta (`EXPIRED`).
     */
    fun closeAuction(
        listing: Listing,
        bids: List<Bid>,
        now: Instant = Instant.now()
    ): AuctionClosureOutcome {
        require(listing.saleMode == SaleMode.AUCTION) { "La publicación no está en modo subasta" }
        val config = requireNotNull(listing.parsedAuctionConfig) {
            "La subasta no tiene una configuración válida"
        }
        require(!now.isBefore(config.endsAt) || listing.status == ListingStatus.AUCTION_ENDED) {
            "La subasta aún no ha finalizado"
        }

        val validBids = bids.filter {
            it.listingId == listing.id &&
                (it.status == BidStatus.VALID || it.status == BidStatus.WINNING)
        }
        val highestBid = validBids.sortedWith(
            compareByDescending<Bid> { it.amount }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        ).firstOrNull()

        if (highestBid == null) {
            val finalState = AuctionState.EXPIRED
            return AuctionClosureOutcome(
                transitionHistory = listOf(AuctionState.AUCTION_ENDED, finalState),
                finalState = finalState,
                updatedListing = listing.copy(status = ListingStatus.EXPIRED),
                winningBid = null,
                updatedBids = bids
            )
        }

        val reserveMet = config.reservePrice == null || highestBid.amount >= config.reservePrice
        return if (reserveMet) {
            val finalState = AuctionState.AWARDED
            val updatedBids = bids.map { bid ->
                when {
                    bid.id == highestBid.id -> bid.copy(status = BidStatus.WINNING)
                    bid.status == BidStatus.VALID || bid.status == BidStatus.WINNING ->
                        bid.copy(status = BidStatus.LOSING)
                    else -> bid
                }
            }
            val winner = updatedBids.first { it.id == highestBid.id }
            AuctionClosureOutcome(
                transitionHistory = listOf(AuctionState.AUCTION_ENDED, finalState),
                finalState = finalState,
                updatedListing = listing.copy(
                    status = ListingStatus.AWARDED,
                    price = winner.amount
                ),
                winningBid = winner,
                updatedBids = updatedBids
            )
        } else {
            val finalState = AuctionState.NO_RESERVE_MET
            val updatedBids = bids.map { bid ->
                if (bid.status == BidStatus.VALID || bid.status == BidStatus.WINNING) {
                    bid.copy(status = BidStatus.LOSING)
                } else {
                    bid
                }
            }
            AuctionClosureOutcome(
                transitionHistory = listOf(AuctionState.AUCTION_ENDED, finalState),
                finalState = finalState,
                updatedListing = listing.copy(status = ListingStatus.NO_RESERVE_MET),
                winningBid = null,
                updatedBids = updatedBids
            )
        }
    }
}

/**
 * Validador de seguridad y reglas de negocio para pujar en una subasta.
 */
object BidValidator {

    const val MAX_BIDS_PER_USER = 3

    const val MSG_NOT_AUCTION = "Esta publicación no admite pujas"
    const val MSG_OWNER_CANNOT_BID = "No puedes pujar en tu propia publicación"
    const val MSG_NOT_STARTED = "La subasta aún no ha comenzado"
    const val MSG_ALREADY_ENDED = "La subasta ya ha finalizado"
    const val MSG_ACCOUNT_RESTRICTED = "Tu cuenta no está habilitada para pujar"
    const val MSG_IDENTITY_REQUIRED = "Debes verificar tu identidad para pujar en esta subasta"
    const val MSG_OPEN_DISPUTE = "No puedes pujar mientras tengas una disputa abierta"
    const val MSG_MAX_BIDS_REACHED = "Has alcanzado el límite máximo de 3 pujas en esta subasta"

    fun minimumRequiredBid(config: AuctionConfig, currentHighestBid: BigDecimal?): BigDecimal =
        if (currentHighestBid == null) {
            config.startingPrice
        } else {
            currentHighestBid + config.minIncrement
        }

    fun validateBid(
        listing: Listing,
        bidder: User,
        amount: BigDecimal,
        existingBids: List<Bid>,
        hasOpenDispute: Boolean,
        now: Instant = Instant.now()
    ): String? {
        if (listing.saleMode != SaleMode.AUCTION) return MSG_NOT_AUCTION
        val config = listing.parsedAuctionConfig ?: return MSG_NOT_AUCTION
        if (listing.ownerId == bidder.id) return MSG_OWNER_CANNOT_BID

        if (bidder.status == UserStatus.SUSPENDED || bidder.status == UserStatus.BANNED) {
            return MSG_ACCOUNT_RESTRICTED
        }

        val state = AuctionStateMachine.resolveState(listing, now)
        when (state) {
            AuctionState.AUCTION_SCHEDULED -> return MSG_NOT_STARTED
            AuctionState.AUCTION_ENDED,
            AuctionState.AWARDED,
            AuctionState.NO_RESERVE_MET,
            AuctionState.EXPIRED -> return MSG_ALREADY_ENDED
            AuctionState.FIXED -> return MSG_NOT_AUCTION
            AuctionState.AUCTION_LIVE -> Unit
        }

        // Seguridad 1: Pujar requiere verification_level='identity' si requires_identity=true
        if (config.requiresIdentity && bidder.verificationLevel != VerificationLevel.IDENTITY) {
            return MSG_IDENTITY_REQUIRED
        }

        // Seguridad 2: No se puede pujar si el usuario tiene una disputa abierta
        if (hasOpenDispute) {
            return MSG_OPEN_DISPUTE
        }

        // Seguridad 3: Máximo 3 pujas por usuario por subasta
        val userBidsCount = existingBids.count {
            it.listingId == listing.id && it.bidderId == bidder.id
        }
        if (userBidsCount >= MAX_BIDS_PER_USER) {
            return MSG_MAX_BIDS_REACHED
        }

        // Validación de monto mínimo
        val currentHighest = existingBids
            .filter {
                it.listingId == listing.id &&
                    (it.status == BidStatus.VALID || it.status == BidStatus.WINNING)
            }
            .maxOfOrNull { it.amount }
        val minRequired = minimumRequiredBid(config, currentHighest)
        if (amount < minRequired) {
            return "La puja mínima es de ${minRequired.stripTrailingZeros().toPlainString()} ${listing.currency}"
        }

        return null
    }
}
