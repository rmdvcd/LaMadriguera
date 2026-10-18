package com.lamadriguera.app.feature.listings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.AuctionConfig
import com.lamadriguera.app.domain.model.BidVisibility
import com.lamadriguera.app.domain.model.Category
import com.lamadriguera.app.domain.model.CubanMunicipalities
import com.lamadriguera.app.domain.model.CubanMunicipality
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.ListingDraft
import com.lamadriguera.app.domain.model.ModeratedContent
import com.lamadriguera.app.domain.model.ModerationDecision
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingVisibility
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.domain.repository.AuctionRepository
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.CategoriesRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.usecase.AuctionConfigValidator
import com.lamadriguera.app.domain.usecase.ImageCompressor
import com.lamadriguera.app.domain.usecase.ImageUploadGate
import com.lamadriguera.app.domain.usecase.ModerateContentUseCase
import com.lamadriguera.app.domain.usecase.ListingValidator
import com.lamadriguera.app.feature.auction.AuctionConfigUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado del formulario de creación y edición de ofertas y demandas.
 */
data class CreateListingUiState(
    val isLoading: Boolean = true,
    val editingListingId: String? = null,
    val kind: ListingKind = ListingKind.OFFER,
    val titleInput: String = "",
    val descriptionInput: String = "",
    val selectedCategoryId: String = "",
    val categories: List<Category> = emptyList(),
    val priceInput: String = "",
    val priceNegotiable: Boolean = false,
    val selectedMunicipalityId: Int? = CubanMunicipalities.all.first().id,
    val municipalities: List<CubanMunicipality> = CubanMunicipalities.all,
    val radiusKm: Int = 10,
    val privacyLevel: PrivacyLevel = PrivacyLevel.PUBLIC,
    val visibility: ListingVisibility = ListingVisibility.PUBLIC,
    val compressedPhotoBytes: ByteArray? = null,
    val existingPhotoUrl: String? = null,
    val allowAuction: Boolean = false,
    val showAuctionConfigScreen: Boolean = false,
    val auctionConfigState: AuctionConfigUiState = AuctionConfigUiState(),
    val auctionConfig: AuctionConfig? = null,
    val canEditAuction: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null,
    val savedListing: Listing? = null
) {
    val photoSizeKb: Int?
        get() = compressedPhotoBytes?.let { (it.size + 1023) / 1024 }
}

@HiltViewModel
class CreateListingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val categoriesRepository: CategoriesRepository,
    private val listingsRepository: ListingsRepository,
    private val auctionRepository: AuctionRepository,
    private val imageCompressor: ImageCompressor,
    private val moderateContent: ModerateContentUseCase,
    private val imageUploadGate: ImageUploadGate
) : ViewModel() {

    private val editingListingId: String? = savedStateHandle["listingId"]

    private val _uiState = MutableStateFlow(
        CreateListingUiState(editingListingId = editingListingId)
    )
    val uiState: StateFlow<CreateListingUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val session = when (val res = authRepository.currentSession()) {
                is Result.Success -> res.data
                is Result.Error -> null
            }
            val categories = when (val res = categoriesRepository.list(page = 0, pageSize = 100)) {
                is Result.Success -> res.data.ifEmpty {
                    listOf(Category(id = DEFAULT_CATEGORY_ID, name = "General"))
                }
                is Result.Error -> listOf(Category(id = DEFAULT_CATEGORY_ID, name = "General"))
            }
            val defaultPrivacy = session?.profile?.privacyLevel ?: PrivacyLevel.PUBLIC
            val defaultMunicipality = session?.profile?.municipalityId
                ?: CubanMunicipalities.all.first().id

            if (!editingListingId.isNullOrBlank()) {
                val existing = when (val res = listingsRepository.find(editingListingId)) {
                    is Result.Success -> res.data
                    is Result.Error -> null
                }
                if (existing != null) {
                    val bidsCount = when (val a = auctionRepository.getAuction(editingListingId)) {
                        is Result.Success -> a.data.bidsCount
                        is Result.Error -> 0
                    }
                    val canEditAuction = AuctionConfigValidator.canEdit(
                        listing = existing,
                        currentUserId = session?.user?.id,
                        bidsCount = bidsCount
                    )
                    val parsedConfig = existing.parsedAuctionConfig
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            kind = existing.kind,
                            titleInput = existing.title,
                            descriptionInput = existing.description,
                            selectedCategoryId = existing.categoryId,
                            categories = categories,
                            priceInput = existing.price.stripTrailingZeros().toPlainString(),
                            priceNegotiable = existing.priceNegotiable,
                            selectedMunicipalityId = existing.municipalityId ?: defaultMunicipality,
                            radiusKm = existing.radiusKm,
                            privacyLevel = existing.privacyLevel,
                            visibility = existing.visibility,
                            existingPhotoUrl = existing.photoUrl,
                            allowAuction = existing.saleMode == SaleMode.AUCTION,
                            auctionConfig = parsedConfig,
                            auctionConfigState = AuctionConfigUiState.fromConfig(
                                config = parsedConfig,
                                canEdit = canEditAuction
                            ),
                            canEditAuction = canEditAuction
                        )
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    categories = categories,
                    selectedCategoryId = categories.first().id,
                    privacyLevel = defaultPrivacy,
                    selectedMunicipalityId = defaultMunicipality
                )
            }
        }
    }

    fun onKindChanged(kind: ListingKind) {
        _uiState.update {
            it.copy(
                kind = kind,
                visibility = if (kind == ListingKind.OFFER) {
                    ListingVisibility.PUBLIC
                } else {
                    it.visibility
                },
                errorMessage = null
            )
        }
    }

    fun onTitleChanged(title: String) {
        _uiState.update { it.copy(titleInput = title, errorMessage = null) }
    }

    fun onDescriptionChanged(description: String) {
        _uiState.update { it.copy(descriptionInput = description, errorMessage = null) }
    }

    fun onCategorySelected(categoryId: String) {
        _uiState.update { it.copy(selectedCategoryId = categoryId, errorMessage = null) }
    }

    fun onPriceChanged(price: String) {
        _uiState.update { it.copy(priceInput = price, errorMessage = null) }
    }

    fun onPriceNegotiableChanged(negotiable: Boolean) {
        _uiState.update { it.copy(priceNegotiable = negotiable, errorMessage = null) }
    }

    fun onMunicipalitySelected(municipalityId: Int?) {
        _uiState.update { it.copy(selectedMunicipalityId = municipalityId, errorMessage = null) }
    }

    fun onRadiusChanged(radiusKm: Int) {
        _uiState.update { it.copy(radiusKm = radiusKm.coerceIn(0, 200), errorMessage = null) }
    }

    fun onPrivacyLevelChanged(privacyLevel: PrivacyLevel) {
        _uiState.update { it.copy(privacyLevel = privacyLevel, errorMessage = null) }
    }

    fun onVisibilityChanged(visibility: ListingVisibility) {
        _uiState.update {
            val allowed = if (it.kind == ListingKind.OFFER) ListingVisibility.PUBLIC else visibility
            it.copy(visibility = allowed, errorMessage = null)
        }
    }

    /**
     * Comprime la imagen en el cliente con [ImageCompressor] garantizando <= 1 MB.
     */
    fun onPhotoSelected(rawBytes: ByteArray) {
        when (val result = imageCompressor.compressForListing(rawBytes)) {
            is Result.Success -> {
                // IA en imágenes (NSFW.js en cliente): si es dudosa, no se sube.
                val compressed = result.data
                viewModelScope.launch {
                    when (val gate = imageUploadGate.check(compressed)) {
                        is Result.Success -> when (val outcome = gate.data) {
                            is ImageUploadGate.Outcome.Allowed -> _uiState.update {
                                it.copy(compressedPhotoBytes = compressed, errorMessage = null)
                            }
                            is ImageUploadGate.Outcome.Refused -> _uiState.update {
                                it.copy(compressedPhotoBytes = null, errorMessage = outcome.message)
                            }
                        }
                        is Result.Error -> _uiState.update {
                            it.copy(errorMessage = gate.message ?: ListingValidator.MSG_PHOTO_FORMAT)
                        }
                    }
                }
            }
            is Result.Error -> _uiState.update {
                it.copy(
                    errorMessage = result.message ?: ListingValidator.MSG_PHOTO_TOO_LARGE
                )
            }
        }
    }

    fun onPhotoRemoved() {
        _uiState.update {
            it.copy(compressedPhotoBytes = null, existingPhotoUrl = null, errorMessage = null)
        }
    }

    /**
     * Switch "¿Permitir subasta?" -> si se activa, muestra [AuctionConfigScreen].
     */
    fun onAllowAuctionChanged(allow: Boolean) {
        val state = _uiState.value
        if (!state.canEditAuction) {
            _uiState.update { it.copy(errorMessage = AuctionConfigValidator.MSG_HAS_BIDS) }
            return
        }
        val initialStartingPrice = state.priceInput.trim().toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?.stripTrailingZeros()?.toPlainString()
            ?: state.auctionConfigState.startingPriceInput

        _uiState.update {
            it.copy(
                allowAuction = allow,
                showAuctionConfigScreen = allow,
                auctionConfigState = it.auctionConfigState.copy(
                    startingPriceInput = initialStartingPrice
                ),
                errorMessage = null
            )
        }
    }

    fun openAuctionConfigScreen() {
        _uiState.update { it.copy(showAuctionConfigScreen = true, errorMessage = null) }
    }

    fun closeAuctionConfigScreen() {
        _uiState.update {
            it.copy(
                showAuctionConfigScreen = false,
                allowAuction = it.auctionConfig != null
            )
        }
    }

    // Delegados de edición de AuctionConfigScreen embebida
    fun onAuctionStartingPriceChanged(value: String) {
        _uiState.update {
            val money = (value.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    startingPriceInput = value,
                    requiresIdentity = if (money) true else it.auctionConfigState.requiresIdentity,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionMinIncrementChanged(value: String) {
        _uiState.update {
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    minIncrementInput = value,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionReservePriceChanged(value: String) {
        _uiState.update {
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    reservePriceInput = value,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionStartsAtChanged(value: String) {
        _uiState.update {
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    startsAtInput = value,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionEndsAtChanged(value: String) {
        _uiState.update {
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    endsAtInput = value,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionAutoExtendChanged(value: String) {
        _uiState.update {
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    autoExtendMinutesInput = value,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionRequiresIdentityChanged(value: Boolean) {
        _uiState.update {
            val enforced = if (it.auctionConfigState.isMoneyAuction) true else value
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    requiresIdentity = enforced,
                    validationError = null
                )
            )
        }
    }

    fun onAuctionVisibleBidsChanged(value: BidVisibility) {
        _uiState.update {
            it.copy(
                auctionConfigState = it.auctionConfigState.copy(
                    visibleBids = value,
                    validationError = null
                )
            )
        }
    }

    fun confirmAuctionConfig() {
        val configState = _uiState.value.auctionConfigState
        val err = configState.validate()
        if (err != null) {
            _uiState.update {
                it.copy(auctionConfigState = configState.copy(validationError = err))
            }
            return
        }
        val config = requireNotNull(configState.toAuctionConfigOrNull())
        _uiState.update {
            it.copy(
                allowAuction = true,
                auctionConfig = config,
                priceInput = config.startingPrice.stripTrailingZeros().toPlainString(),
                showAuctionConfigScreen = false,
                errorMessage = null
            )
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.isSubmitting) return

        val parsedPrice = if (state.allowAuction && state.auctionConfig != null) {
            state.auctionConfig.startingPrice
        } else if (state.priceInput.isBlank() && state.priceNegotiable) {
            BigDecimal.ZERO
        } else {
            state.priceInput.trim().toBigDecimalOrNull() ?: BigDecimal("-1")
        }

        val draft = ListingDraft(
            kind = state.kind,
            title = state.titleInput,
            description = state.descriptionInput,
            categoryId = state.selectedCategoryId,
            price = parsedPrice,
            priceNegotiable = state.priceNegotiable,
            municipalityId = state.selectedMunicipalityId,
            radiusKm = state.radiusKm,
            privacyLevel = state.privacyLevel,
            visibility = if (state.kind == ListingKind.OFFER) {
                ListingVisibility.PUBLIC
            } else {
                state.visibility
            },
            photoBytes = state.compressedPhotoBytes,
            existingPhotoUrl = state.existingPhotoUrl,
            allowAuction = state.allowAuction,
            auctionConfig = if (state.allowAuction) state.auctionConfig else null
        )

        ListingValidator.validate(draft)?.let { validationError ->
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            // Moderación de texto (reglas de dominio + IA) ANTES de publicar.
            val decision = when (
                val moderation = moderateContent.moderate(
                    ModeratedContent(
                        targetType = ModerationTargetType.LISTING,
                        title = draft.title,
                        body = draft.description
                    )
                )
            ) {
                is Result.Success -> moderation.data
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = moderation.message ?: "No se pudo verificar el contenido"
                        )
                    }
                    return@launch
                }
            }
            if (decision is ModerationDecision.Blocked) {
                _uiState.update { it.copy(isSubmitting = false, errorMessage = decision.userMessage) }
                return@launch
            }

            val result = if (state.editingListingId.isNullOrBlank()) {
                listingsRepository.createFromDraft(draft)
            } else {
                listingsRepository.updateFromDraft(state.editingListingId, draft)
            }
            when (result) {
                is Result.Success -> {
                    if (decision is ModerationDecision.Doubtful) {
                        // Solo lo dudoso (umbral bajo) llega a la cola humana.
                        moderateContent.flagDoubtful(
                            targetType = ModerationTargetType.LISTING,
                            targetId = result.data.id,
                            decision = decision
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            savedListing = result.data,
                            message = decision.userMessage.ifBlank { null }
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.message ?: "No se pudo guardar la publicación"
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CATEGORY_ID = "40000000-0000-0000-0000-000000000001"
    }
}
