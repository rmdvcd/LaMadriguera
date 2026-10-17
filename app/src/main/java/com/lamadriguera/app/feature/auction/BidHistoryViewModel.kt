package com.lamadriguera.app.feature.auction

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Bid
import com.lamadriguera.app.domain.model.BidVisibility
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.repository.AuctionRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de UI de [BidHistoryScreen].
 * Solo muestra el listado si `visible_bids = 'all'`.
 */
data class BidHistoryUiState(
    val isLoading: Boolean = true,
    val listing: Listing? = null,
    val visibleBids: BidVisibility = BidVisibility.OWNER,
    val bids: List<Bid> = emptyList(),
    val errorMessage: String? = null
) {
    val isHistoryVisible: Boolean
        get() = visibleBids == BidVisibility.ALL
}

@HiltViewModel
class BidHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listingsRepository: ListingsRepository,
    private val auctionRepository: AuctionRepository
) : ViewModel() {

    private val listingId: String = savedStateHandle["listingId"] ?: ""

    private val _uiState = MutableStateFlow(BidHistoryUiState())
    val uiState: StateFlow<BidHistoryUiState> = _uiState.asStateFlow()

    init {
        if (listingId.isNotBlank()) {
            load(listingId)
        } else {
            _uiState.update { it.copy(isLoading = false, errorMessage = "Subasta no especificada") }
        }
    }

    fun refresh() {
        if (listingId.isNotBlank()) load(listingId)
    }

    fun load(targetListingId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val listing = when (val found = listingsRepository.find(targetListingId)) {
                is Result.Success -> found.data
                is Result.Error -> null
            }
            if (listing == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Publicación no encontrada")
                }
                return@launch
            }
            val config = listing.parsedAuctionConfig
            val visibility = config?.visibleBids ?: BidVisibility.OWNER
            if (!AuctionConfigValidator.canShowBidHistory(config)) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        listing = listing,
                        visibleBids = visibility,
                        bids = emptyList()
                    )
                }
                return@launch
            }

            when (val bidsResult = auctionRepository.getBidHistory(targetListingId)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        listing = listing,
                        visibleBids = visibility,
                        bids = bidsResult.data
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        listing = listing,
                        visibleBids = visibility,
                        errorMessage = bidsResult.message ?: "No se pudo cargar el historial de pujas"
                    )
                }
            }
        }
    }
}
