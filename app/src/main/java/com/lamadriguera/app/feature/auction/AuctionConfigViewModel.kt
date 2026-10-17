package com.lamadriguera.app.feature.auction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.BidVisibility
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.repository.AuctionRepository
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de UI de [AuctionConfigScreen].
 */
data class AuctionConfigUiState(
    val isLoading: Boolean = false,
    val listing: Listing? = null,
    val startingPriceInput: String = "100",
    val minIncrementInput: String = "10",
    val reservePriceInput: String = "",
    val startsAtInput: String = Instant.now().truncatedTo(ChronoUnit.MINUTES).toString(),
    val endsAtInput: String = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES).toString(),
    val autoExtendMinutesInput: String = AuctionConfig.DEFAULT_AUTO_EXTEND_MINUTES.toString(),
    val requiresIdentity: Boolean = true,
    val visibleBids: BidVisibility = BidVisibility.ALL,
    val canEdit: Boolean = true,
    val isSaving: Boolean = false,
    val validationError: String? = null,
    val savedConfig: AuctionConfig? = null,
    val savedListing: Listing? = null
) {
    /** Si el precio inicial es > 0 (subasta con dinero), requires_identity es true. */
    val isMoneyAuction: Boolean
        get() = (startingPriceInput.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO

    fun toAuctionConfigOrNull(): AuctionConfig? {
        val startingPrice = startingPriceInput.trim().toBigDecimalOrNull() ?: return null
        val minIncrement = minIncrementInput.trim().toBigDecimalOrNull() ?: return null
        val reservePrice = if (reservePriceInput.isBlank()) {
            null
        } else {
            reservePriceInput.trim().toBigDecimalOrNull() ?: return null
        }
        val startsAt = runCatching { Instant.parse(startsAtInput.trim()) }.getOrNull() ?: return null
        val endsAt = runCatching { Instant.parse(endsAtInput.trim()) }.getOrNull() ?: return null
        val autoExtend = autoExtendMinutesInput.trim().toIntOrNull()
            ?: AuctionConfig.DEFAULT_AUTO_EXTEND_MINUTES
        val effectiveRequiresIdentity = if (startingPrice > BigDecimal.ZERO) true else requiresIdentity
        return AuctionConfig(
            startingPrice = startingPrice,
            minIncrement = minIncrement,
            reservePrice = reservePrice,
            startsAt = startsAt,
            endsAt = endsAt,
            autoExtendMinutes = autoExtend,
            requiresIdentity = effectiveRequiresIdentity,
            visibleBids = visibleBids
        )
    }

    fun validate(): String? {
        val startingPrice = startingPriceInput.trim().toBigDecimalOrNull()
        if (startingPrice == null || startingPrice <= BigDecimal.ZERO) {
            return AuctionConfigValidator.MSG_STARTING_PRICE
        }
        val minIncrement = minIncrementInput.trim().toBigDecimalOrNull()
        if (minIncrement == null || minIncrement <= BigDecimal.ZERO) {
            return AuctionConfigValidator.MSG_MIN_INCREMENT
        }
        val reservePrice = if (reservePriceInput.isBlank()) {
            null
        } else {
            val parsed = reservePriceInput.trim().toBigDecimalOrNull()
                ?: return AuctionConfigValidator.MSG_RESERVE_PRICE
            if (parsed < startingPrice) return AuctionConfigValidator.MSG_RESERVE_PRICE
            parsed
        }
        val startsAt = runCatching { Instant.parse(startsAtInput.trim()) }.getOrNull()
            ?: return "Formato de fecha de inicio no válido (usa ISO-8601, ej. 2026-10-18T12:00:00Z)"
        val endsAt = runCatching { Instant.parse(endsAtInput.trim()) }.getOrNull()
            ?: return "Formato de fecha de cierre no válido (usa ISO-8601, ej. 2026-10-19T12:00:00Z)"
        if (!endsAt.isAfter(startsAt)) {
            return AuctionConfigValidator.MSG_ENDS_AFTER_STARTS
        }
        val autoExtend = autoExtendMinutesInput.trim().toIntOrNull()
            ?: return AuctionConfigValidator.MSG_AUTO_EXTEND
        if (autoExtend < 0) return AuctionConfigValidator.MSG_AUTO_EXTEND

        val config = AuctionConfig(
            startingPrice = startingPrice,
            minIncrement = minIncrement,
            reservePrice = reservePrice,
            startsAt = startsAt,
            endsAt = endsAt,
            autoExtendMinutes = autoExtend,
            requiresIdentity = if (startingPrice > BigDecimal.ZERO) true else requiresIdentity,
            visibleBids = visibleBids
        )
        return AuctionConfigValidator.validate(config)
    }

    companion object {
        fun fromConfig(config: AuctionConfig?, canEdit: Boolean = true): AuctionConfigUiState {
            if (config == null) return AuctionConfigUiState(canEdit = canEdit)
            return AuctionConfigUiState(
                startingPriceInput = config.startingPrice.stripTrailingZeros().toPlainString(),
                minIncrementInput = config.minIncrement.stripTrailingZeros().toPlainString(),
                reservePriceInput = config.reservePrice?.stripTrailingZeros()?.toPlainString().orEmpty(),
                startsAtInput = config.startsAt.toString(),
                endsAtInput = config.endsAt.toString(),
                autoExtendMinutesInput = config.autoExtendMinutes.toString(),
                requiresIdentity = config.requiresIdentity,
                visibleBids = config.visibleBids,
                canEdit = canEdit
            )
        }
    }
}

@HiltViewModel
class AuctionConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val listingsRepository: ListingsRepository,
    private val auctionRepository: AuctionRepository
) : ViewModel() {

    private val listingId: String? = savedStateHandle["listingId"]

    private val _uiState = MutableStateFlow(AuctionConfigUiState())
    val uiState: StateFlow<AuctionConfigUiState> = _uiState.asStateFlow()

    init {
        if (!listingId.isNullOrBlank()) {
            loadForListing(listingId)
        }
    }

    fun loadForListing(targetListingId: String) {
        _uiState.update { it.copy(isLoading = true, validationError = null) }
        viewModelScope.launch {
            val currentUserId = when (val session = authRepository.currentSession()) {
                is Result.Success -> session.data?.user?.id
                is Result.Error -> null
            }
            val listing = when (val res = listingsRepository.find(targetListingId)) {
                is Result.Success -> res.data
                is Result.Error -> null
            }
            if (listing == null) {
                _uiState.update {
                    it.copy(isLoading = false, canEdit = false, validationError = "Publicación no encontrada")
                }
                return@launch
            }
            val bidsCount = when (val auction = auctionRepository.getAuction(targetListingId)) {
                is Result.Success -> auction.data.bidsCount
                is Result.Error -> 0
            }
            val editError = AuctionConfigValidator.validateCanEdit(listing, currentUserId, bidsCount)
            val existingConfig = listing.parsedAuctionConfig
            _uiState.value = AuctionConfigUiState.fromConfig(
                config = existingConfig,
                canEdit = editError == null
            ).copy(
                isLoading = false,
                listing = listing,
                validationError = editError
            )
        }
    }

    fun onStartingPriceChanged(value: String) {
        _uiState.update {
            val money = (value.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
            it.copy(
                startingPriceInput = value,
                requiresIdentity = if (money) true else it.requiresIdentity,
                validationError = null
            )
        }
    }

    fun onMinIncrementChanged(value: String) {
        _uiState.update { it.copy(minIncrementInput = value, validationError = null) }
    }

    fun onReservePriceChanged(value: String) {
        _uiState.update { it.copy(reservePriceInput = value, validationError = null) }
    }

    fun onStartsAtChanged(value: String) {
        _uiState.update { it.copy(startsAtInput = value, validationError = null) }
    }

    fun onEndsAtChanged(value: String) {
        _uiState.update { it.copy(endsAtInput = value, validationError = null) }
    }

    fun onAutoExtendMinutesChanged(value: String) {
        _uiState.update { it.copy(autoExtendMinutesInput = value, validationError = null) }
    }

    fun onRequiresIdentityChanged(value: Boolean) {
        _uiState.update {
            val enforced = if (it.isMoneyAuction) true else value
            it.copy(requiresIdentity = enforced, validationError = null)
        }
    }

    fun onVisibleBidsChanged(value: BidVisibility) {
        _uiState.update { it.copy(visibleBids = value, validationError = null) }
    }

    fun saveConfiguration() {
        val state = _uiState.value
        if (!state.canEdit) {
            _uiState.update { it.copy(validationError = AuctionConfigValidator.MSG_HAS_BIDS) }
            return
        }
        val error = state.validate()
        if (error != null) {
            _uiState.update { it.copy(validationError = error) }
            return
        }
        val config = requireNotNull(state.toAuctionConfigOrNull())
        val targetListing = state.listing
        if (targetListing == null) {
            _uiState.update { it.copy(savedConfig = config, validationError = null) }
            return
        }

        _uiState.update { it.copy(isSaving = true, validationError = null) }
        viewModelScope.launch {
            when (val result = auctionRepository.updateAuctionConfig(targetListing.id, config)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        listing = result.data,
                        savedConfig = config,
                        savedListing = result.data
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        validationError = result.message ?: "No se pudo actualizar la subasta"
                    )
                }
            }
        }
    }
}
