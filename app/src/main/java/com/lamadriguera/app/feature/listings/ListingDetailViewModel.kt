package com.lamadriguera.app.feature.listings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Auction
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.AuctionState
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.Profile
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.model.VerificationLevel
import com.lamadriguera.app.domain.repository.AuctionRepository
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.CategoriesRepository
import com.lamadriguera.app.domain.repository.ChatRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.repository.ProfilesRepository
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import com.lamadriguera.app.domain.usecase.AuctionStateMachine
import com.lamadriguera.app.domain.usecase.BidValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Estado de UI de [ListingDetailScreen]: precio fijo o contador de subasta,
 * última puja, tiempo restante y controles de puja/configuración/historial.
 */
data class ListingDetailUiState(
    val isLoading: Boolean = true,
    val listing: Listing? = null,
    val ownerProfile: Profile? = null,
    val categoryName: String = "General",
    val currentUser: User? = null,
    val auction: Auction? = null,
    val auctionState: AuctionState = AuctionState.FIXED,
    val remainingSeconds: Long = 0L,
    val hasOpenDispute: Boolean = false,
    val bidAmountInput: String = "",
    val isPlacingBid: Boolean = false,
    val isClosingAuction: Boolean = false,
    val isOpeningChat: Boolean = false,
    val chatConversationId: String? = null,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null
) {
    val auctionConfig: AuctionConfig?
        get() = listing?.parsedAuctionConfig ?: auction?.config

    val isOwner: Boolean
        get() = currentUser != null && listing?.ownerId == currentUser.id

    /** Solo el owner puede editar la config mientras no haya pujas. */
    val canEditAuctionConfig: Boolean
        get() {
            val l = listing ?: return false
            val bidsCount = auction?.bidsCount ?: 0
            return AuctionConfigValidator.canEdit(l, currentUser?.id, bidsCount)
        }

    /** BidHistoryScreen solo si visible_bids='all'. */
    val canOpenBidHistory: Boolean
        get() = listing?.saleMode == SaleMode.AUCTION &&
            AuctionConfigValidator.canShowBidHistory(auctionConfig)

    val requiresIdentityForBid: Boolean
        get() = auctionConfig?.requiresIdentity == true

    val userMissingRequiredIdentity: Boolean
        get() = requiresIdentityForBid &&
            currentUser?.verificationLevel != VerificationLevel.IDENTITY

    val userReachedMaxBids: Boolean
        get() = (auction?.userBidsCount ?: 0) >= BidValidator.MAX_BIDS_PER_USER

    val canPlaceBid: Boolean
        get() = listing?.saleMode == SaleMode.AUCTION &&
            !isOwner &&
            auctionState == AuctionState.AUCTION_LIVE &&
            !userMissingRequiredIdentity &&
            !hasOpenDispute &&
            !userReachedMaxBids &&
            !isPlacingBid
}

@HiltViewModel
class ListingDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listingsRepository: ListingsRepository,
    private val auctionRepository: AuctionRepository,
    private val profilesRepository: ProfilesRepository,
    private val categoriesRepository: CategoriesRepository,
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val listingId: String = savedStateHandle["listingId"]
        ?: savedStateHandle["auctionId"]
        ?: ""

    private val _uiState = MutableStateFlow(ListingDetailUiState())
    val uiState: StateFlow<ListingDetailUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    init {
        if (listingId.isNotBlank()) {
            load(listingId)
        } else {
            _uiState.update {
                it.copy(isLoading = false, errorMessage = "Publicación no encontrada")
            }
        }
    }

    fun refresh() {
        if (listingId.isNotBlank()) load(listingId)
    }

    fun load(targetId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, feedbackMessage = null) }
        viewModelScope.launch {
            val session = when (val s = authRepository.currentSession()) {
                is Result.Success -> s.data
                is Result.Error -> null
            }
            val currentUser = session?.user
            val listing = when (val found = listingsRepository.find(targetId)) {
                is Result.Success -> found.data
                is Result.Error -> null
            }
            if (listing == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Publicación no encontrada")
                }
                return@launch
            }

            val ownerProfile = when (val p = profilesRepository.find(listing.ownerId)) {
                is Result.Success -> p.data
                is Result.Error -> null
            }
            val categoryName = when (val c = categoriesRepository.find(listing.categoryId)) {
                is Result.Success -> c.data?.name ?: "General"
                is Result.Error -> "General"
            }
            val openDispute = if (currentUser != null) {
                when (val d = auctionRepository.hasOpenDispute(currentUser.id)) {
                    is Result.Success -> d.data
                    is Result.Error -> false
                }
            } else {
                false
            }
            val auction = if (listing.saleMode == SaleMode.AUCTION) {
                when (val a = auctionRepository.getAuction(listing.id)) {
                    is Result.Success -> a.data
                    is Result.Error -> null
                }
            } else {
                null
            }

            val now = Instant.now()
            val resolvedState = AuctionStateMachine.resolveState(listing, now)
            val remaining = computeRemainingSeconds(listing, resolvedState, now)
            val defaultNextBid = auction?.minimumNextBid
                ?: listing.parsedAuctionConfig?.startingPrice
                ?: listing.price

            _uiState.update {
                it.copy(
                    isLoading = false,
                    listing = listing,
                    ownerProfile = ownerProfile,
                    categoryName = categoryName,
                    currentUser = currentUser,
                    auction = auction,
                    auctionState = resolvedState,
                    remainingSeconds = remaining,
                    hasOpenDispute = openDispute,
                    bidAmountInput = it.bidAmountInput.ifBlank {
                        defaultNextBid.stripTrailingZeros().toPlainString()
                    }
                )
            }

            if (listing.saleMode == SaleMode.AUCTION) {
                startCountdownTicker()
            } else {
                countdownJob?.cancel()
            }
        }
    }

    fun onBidAmountChanged(value: String) {
        _uiState.update { it.copy(bidAmountInput = value, errorMessage = null, feedbackMessage = null) }
    }

    /**
     * Abre (o crea) la conversación cifrada con el anunciante. La navegación
     * la hace el NavGraph al observar `chatConversationId`.
     */
    fun openChat() {
        val listing = _uiState.value.listing ?: return
        val currentUser = _uiState.value.currentUser ?: return
        if (currentUser.id == listing.ownerId || _uiState.value.isOpeningChat) return
        _uiState.update { it.copy(isOpeningChat = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = chatRepository.getOrCreateConversation(listing.id, listing.ownerId)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isOpeningChat = false,
                        chatConversationId = result.data.conversation.id
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isOpeningChat = false,
                        errorMessage = result.message ?: "No se pudo abrir el chat"
                    )
                }
            }
        }
    }

    /** Resetea el destino de chat tras navegar (evita re-navegar al volver). */
    fun onChatNavigated() {
        _uiState.update { it.copy(chatConversationId = null) }
    }

    fun placeBid() {
        val state = _uiState.value
        val listing = state.listing ?: return
        val amount = state.bidAmountInput.trim().toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _uiState.update { it.copy(errorMessage = "Introduce un monto de puja válido") }
            return
        }
        if (state.userMissingRequiredIdentity) {
            _uiState.update { it.copy(errorMessage = BidValidator.MSG_IDENTITY_REQUIRED) }
            return
        }
        if (state.hasOpenDispute) {
            _uiState.update { it.copy(errorMessage = BidValidator.MSG_OPEN_DISPUTE) }
            return
        }
        if (state.userReachedMaxBids) {
            _uiState.update { it.copy(errorMessage = BidValidator.MSG_MAX_BIDS_REACHED) }
            return
        }

        _uiState.update { it.copy(isPlacingBid = true, errorMessage = null, feedbackMessage = null) }
        viewModelScope.launch {
            when (val result = auctionRepository.placeBid(listing.id, amount)) {
                is Result.Success -> {
                    val refreshedListing = (listingsRepository.find(listing.id) as? Result.Success)?.data ?: listing
                    val refreshedAuction = (auctionRepository.getAuction(listing.id) as? Result.Success)?.data
                    val now = Instant.now()
                    val resolvedState = AuctionStateMachine.resolveState(refreshedListing, now)
                    val nextMin = refreshedAuction?.minimumNextBid ?: amount
                    _uiState.update {
                        it.copy(
                            isPlacingBid = false,
                            listing = refreshedListing,
                            auction = refreshedAuction,
                            auctionState = resolvedState,
                            remainingSeconds = computeRemainingSeconds(refreshedListing, resolvedState, now),
                            bidAmountInput = nextMin.stripTrailingZeros().toPlainString(),
                            feedbackMessage = "Puja registrada por ${result.data.amount.stripTrailingZeros().toPlainString()} ${refreshedListing.currency}"
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isPlacingBid = false,
                        errorMessage = result.message ?: "No se pudo registrar la puja"
                    )
                }
            }
        }
    }

    fun closeAuction() {
        val listing = _uiState.value.listing ?: return
        if (_uiState.value.isClosingAuction) return
        _uiState.update { it.copy(isClosingAuction = true, errorMessage = null) }
        viewModelScope.launch {
            when (val closed = auctionRepository.closeAuction(listing.id)) {
                is Result.Success -> {
                    val refreshedAuction = (auctionRepository.getAuction(listing.id) as? Result.Success)?.data
                    val now = Instant.now()
                    val resolvedState = AuctionStateMachine.resolveState(closed.data, now)
                    _uiState.update {
                        it.copy(
                            isClosingAuction = false,
                            listing = closed.data,
                            auction = refreshedAuction,
                            auctionState = resolvedState,
                            remainingSeconds = 0L
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isClosingAuction = false,
                        errorMessage = closed.message ?: "No se pudo cerrar la subasta"
                    )
                }
            }
        }
    }

    private fun startCountdownTicker() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val current = _uiState.value
                val listing = current.listing ?: break
                val now = Instant.now()
                val newState = AuctionStateMachine.resolveState(listing, now)
                val remaining = computeRemainingSeconds(listing, newState, now)
                _uiState.update {
                    it.copy(
                        auctionState = newState,
                        remainingSeconds = remaining
                    )
                }
                if (newState == AuctionState.AUCTION_ENDED &&
                    !current.isClosingAuction &&
                    listing.status != com.lamadriguera.app.domain.model.ListingStatus.AWARDED &&
                    listing.status != com.lamadriguera.app.domain.model.ListingStatus.NO_RESERVE_MET &&
                    listing.status != com.lamadriguera.app.domain.model.ListingStatus.EXPIRED
                ) {
                    closeAuction()
                    break
                }
            }
        }
    }

    private fun computeRemainingSeconds(
        listing: Listing,
        state: AuctionState,
        now: Instant
    ): Long {
        val config = listing.parsedAuctionConfig ?: return 0L
        val target = when (state) {
            AuctionState.AUCTION_SCHEDULED -> config.startsAt
            AuctionState.AUCTION_LIVE -> config.endsAt
            else -> return 0L
        }
        return Duration.between(now, target).seconds.coerceAtLeast(0L)
    }
}
