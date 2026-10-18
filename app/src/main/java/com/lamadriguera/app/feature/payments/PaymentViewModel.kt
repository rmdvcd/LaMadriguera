package com.lamadriguera.app.feature.payments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.Listing
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.MonetizationSettings
import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.domain.model.PaymentStatus
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.ListingsRepository
import com.lamadriguera.app.domain.repository.PaymentsRepository
import com.lamadriguera.app.domain.usecase.PaymentDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaymentUiState(
    val isLoading: Boolean = true,
    val listing: Listing? = null,
    val products: List<MonetizationProduct> = emptyList(),
    val settings: MonetizationSettings? = null,
    val payment: Payment? = null,
    val lastConfirmedPayment: Payment? = null,
    val history: List<Payment> = emptyList(),
    val historyMode: Boolean = false,
    val isPaymentAdmin: Boolean = false,
    val isBusy: Boolean = false,
    val createdPaymentId: String? = null,
    val message: String? = null,
    val errorMessage: String? = null
)

/** Carga el catálogo, crea órdenes y envía comprobantes; la UI nunca escribe estados de pago. */
@HiltViewModel
class PaymentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val listingsRepository: ListingsRepository,
    private val paymentsRepository: PaymentsRepository
) : ViewModel() {

    private val listingId: String? = savedStateHandle["listingId"]
    private val paymentId: String? = savedStateHandle["paymentId"]

    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            val admin = (paymentsRepository.isPaymentAdmin() as? Result.Success)?.data ?: false
            val settings = (paymentsRepository.getSettings() as? Result.Success)?.data
            val products = (paymentsRepository.listProducts() as? Result.Success)?.data.orEmpty()
            val state = _uiState.value

            if (paymentId != null) {
                val payment = when (val found = paymentsRepository.find(paymentId)) {
                    is Result.Success -> found.data
                    is Result.Error -> null
                }
                if (payment == null) {
                    _uiState.update {
                        it.copy(isLoading = false, isPaymentAdmin = admin, settings = settings,
                            errorMessage = "No se encontró este pago")
                    }
                    return@launch
                }
                val listing = (listingsRepository.find(payment.targetId) as? Result.Success)?.data
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        listing = listing,
                        products = products,
                        settings = settings,
                        payment = payment,
                        isPaymentAdmin = admin,
                        historyMode = false,
                        errorMessage = null
                    )
                }
                return@launch
            }

            if (!listingId.isNullOrBlank()) {
                val session = (authRepository.currentSession() as? Result.Success)?.data
                val listing = (listingsRepository.find(listingId) as? Result.Success)?.data
                if (listing == null || session?.user?.id != listing.ownerId) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isPaymentAdmin = admin,
                            settings = settings,
                            errorMessage = "Solo el dueño puede promocionar esta publicación"
                        )
                    }
                    return@launch
                }
                val latest = (paymentsRepository.latestForListing(listingId) as? Result.Success)?.data
                val openOrRejected = latest?.takeIf { it.status != PaymentStatus.CONFIRMED }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        listing = listing,
                        products = products,
                        settings = settings,
                        payment = openOrRejected,
                        lastConfirmedPayment = latest?.takeIf { it.status == PaymentStatus.CONFIRMED },
                        isPaymentAdmin = admin,
                        historyMode = false,
                        errorMessage = if (settings == null) {
                            "No se pudo cargar la cuenta de destino. Intenta actualizar."
                        } else null
                    )
                }
                return@launch
            }

            val userId = (authRepository.currentSession() as? Result.Success)?.data?.user?.id
            if (userId == null) {
                _uiState.update {
                    it.copy(isLoading = false, isPaymentAdmin = admin, settings = settings,
                        errorMessage = "Inicia sesión para ver tus pagos")
                }
                return@launch
            }
            when (val result = paymentsRepository.listForUser(userId)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        products = products,
                        settings = settings,
                        history = result.data,
                        historyMode = true,
                        isPaymentAdmin = admin,
                        errorMessage = null
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, isPaymentAdmin = admin, settings = settings,
                        errorMessage = result.message ?: "No se pudieron cargar tus pagos")
                }
            }
        }
    }

    fun createOrder(productCode: String) {
        val current = _uiState.value
        val listing = current.listing
        if (listing == null || current.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = paymentsRepository.createOrder(listing.id, productCode)) {
                is Result.Success -> _uiState.update {
                    it.copy(isBusy = false, createdPaymentId = result.data.id)
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo crear la orden")
                }
            }
        }
    }

    fun retryOrder() {
        val payment = _uiState.value.payment ?: return
        val productCode = payment.productCode
        if (productCode.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "No se pudo identificar el producto para reintentar") }
            return
        }
        createOrder(productCode)
    }

    fun submitProof(imageBytes: ByteArray, last4: String, localDateTime: String) {
        val current = _uiState.value
        val payment = current.payment
        if (payment == null || payment.status != PaymentStatus.PENDING || current.isBusy) return
        if (!last4.matches(Regex("^[0-9]{4}$"))) {
            _uiState.update { it.copy(errorMessage = "Escribe los últimos cuatro dígitos de la cuenta de origen") }
            return
        }
        val transferAt = PaymentDateTime.parseLocalInput(localDateTime)
        if (transferAt == null) {
            _uiState.update { it.copy(errorMessage = "Usa fecha y hora con formato ${PaymentDateTime.INPUT_PATTERN}") }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = paymentsRepository.submitProof(payment.id, imageBytes, last4, transferAt)) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isBusy = false,
                        payment = result.data,
                        message = "Comprobante enviado. La publicación quedó pausada mientras se revisa.",
                        errorMessage = null
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo enviar el comprobante")
                }
            }
        }
    }

    fun onPaymentNavigated() {
        _uiState.update { it.copy(createdPaymentId = null) }
    }

    fun formatTransferAt(instant: Instant): String = PaymentDateTime.formatInput(instant)
}
