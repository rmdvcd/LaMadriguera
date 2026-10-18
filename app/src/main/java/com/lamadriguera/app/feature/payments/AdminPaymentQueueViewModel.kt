package com.lamadriguera.app.feature.payments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.MonetizationSettings
import com.lamadriguera.app.domain.model.PaymentReviewItem
import com.lamadriguera.app.domain.repository.PaymentsRepository
import com.lamadriguera.app.domain.usecase.PaymentDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminPaymentQueueUiState(
    val isLoading: Boolean = true,
    val isAdmin: Boolean = false,
    val queue: List<PaymentReviewItem> = emptyList(),
    val products: List<MonetizationProduct> = emptyList(),
    val settings: MonetizationSettings? = null,
    val transferAccountInput: String = "",
    val accountHolderInput: String = "",
    val productPriceInputs: Map<String, String> = emptyMap(),
    val selected: PaymentReviewItem? = null,
    val proofUrl: String? = null,
    val amountInput: String = "",
    val transferAtInput: String = "",
    val transferLast4Input: String = "",
    val referenceInput: String = "",
    val rejectionReasonInput: String = "",
    val blacklistReceipt: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null,
    val message: String? = null
)

/** Panel de revisión/concilación. PostgreSQL revalida rol, monto, hora y duplicados. */
@HiltViewModel
class AdminPaymentQueueViewModel @Inject constructor(
    private val paymentsRepository: PaymentsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminPaymentQueueUiState())
    val uiState: StateFlow<AdminPaymentQueueUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val isAdmin = (paymentsRepository.isPaymentAdmin() as? Result.Success)?.data ?: false
            if (!isAdmin) {
                _uiState.update {
                    it.copy(isLoading = false, isAdmin = false,
                        errorMessage = "Esta pantalla está disponible solo para el rol admin/cobrador")
                }
                return@launch
            }
            val settings = (paymentsRepository.getSettings() as? Result.Success)?.data
            val products = (paymentsRepository.listProducts(includeInactive = true) as? Result.Success)?.data.orEmpty()
            when (val queue = paymentsRepository.reviewQueue()) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAdmin = true,
                        queue = queue.data,
                        settings = settings,
                        products = products,
                        transferAccountInput = settings?.transferAccount.orEmpty(),
                        accountHolderInput = settings?.accountHolder.orEmpty(),
                        productPriceInputs = products.associate { product ->
                            product.code to product.amount.stripTrailingZeros().toPlainString()
                        },
                        errorMessage = null
                    )
                }
                is Result.Error -> _uiState.update {
                    it.copy(isLoading = false, isAdmin = true,
                        errorMessage = queue.message ?: "No se pudo cargar la cola de conciliación")
                }
            }
        }
    }

    fun openPayment(item: PaymentReviewItem) {
        _uiState.update {
            it.copy(
                selected = item,
                proofUrl = null,
                amountInput = item.payment.amount.stripTrailingZeros().toPlainString(),
                transferAtInput = item.payment.transferAt?.let { PaymentDateTime.formatInput(it) }.orEmpty(),
                transferLast4Input = item.payment.transferLast4.orEmpty(),
                referenceInput = "",
                rejectionReasonInput = "",
                blacklistReceipt = false,
                errorMessage = null,
                message = null
            )
        }
        viewModelScope.launch {
            when (val result = paymentsRepository.proofSignedUrl(item.payment.proofRef.orEmpty())) {
                is Result.Success -> _uiState.update { it.copy(proofUrl = result.data) }
                is Result.Error -> _uiState.update {
                    it.copy(errorMessage = result.message ?: "No se pudo abrir la captura privada")
                }
            }
        }
    }

    fun closePayment() {
        _uiState.update { it.copy(selected = null, proofUrl = null, errorMessage = null) }
    }

    fun onAmountChanged(value: String) {
        _uiState.update { it.copy(amountInput = value.filter { char -> char.isDigit() || char == '.' || char == ',' }.take(20)) }
    }

    fun onTransferAtChanged(value: String) {
        _uiState.update { it.copy(transferAtInput = value.take(20)) }
    }

    fun onTransferLast4Changed(value: String) {
        _uiState.update { it.copy(transferLast4Input = value.filter(Char::isDigit).take(4)) }
    }

    fun onReferenceChanged(value: String) {
        _uiState.update { it.copy(referenceInput = value.take(100)) }
    }

    fun onRejectionReasonChanged(value: String) {
        _uiState.update { it.copy(rejectionReasonInput = value.take(500)) }
    }

    fun onBlacklistChanged(value: Boolean) {
        _uiState.update { it.copy(blacklistReceipt = value) }
    }

    fun confirmPayment() {
        val state = _uiState.value
        val item = state.selected ?: return
        val amount = state.amountInput.replace(',', '.').toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _uiState.update { it.copy(errorMessage = "Escribe el monto recibido en el extracto") }
            return
        }
        val transferAt = PaymentDateTime.parseLocalInput(state.transferAtInput)
        if (transferAt == null) {
            _uiState.update { it.copy(errorMessage = "Usa fecha y hora con formato ${PaymentDateTime.INPUT_PATTERN}") }
            return
        }
        if (!state.transferLast4Input.matches(Regex("^[0-9]{4}$"))) {
            _uiState.update { it.copy(errorMessage = "Confirma los últimos cuatro dígitos del extracto") }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = paymentsRepository.confirmPayment(
                paymentId = item.payment.id,
                receivedAmount = amount,
                transferAt = transferAt,
                transferLast4 = state.transferLast4Input,
                reference = state.referenceInput.trim().takeIf(String::isNotEmpty)
            )) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isBusy = false, selected = null, proofUrl = null,
                            message = "Pago ${result.data.code.orEmpty()} confirmado; promoción activada.")
                    }
                    reloadQueue()
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo confirmar el pago")
                }
            }
        }
    }

    fun rejectPayment() {
        val state = _uiState.value
        val item = state.selected ?: return
        if (state.rejectionReasonInput.trim().isBlank()) {
            _uiState.update { it.copy(errorMessage = "Explica el motivo del rechazo") }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = paymentsRepository.rejectPayment(
                paymentId = item.payment.id,
                reason = state.rejectionReasonInput,
                blacklistReceipt = state.blacklistReceipt
            )) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(isBusy = false, selected = null, proofUrl = null,
                            message = "Pago ${result.data.code.orEmpty()} rechazado; el usuario puede reintentar.")
                    }
                    reloadQueue()
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo rechazar el pago")
                }
            }
        }
    }

    fun onTransferAccountChanged(value: String) {
        _uiState.update { it.copy(transferAccountInput = value.filter { c -> c.isDigit() || c == ' ' || c == '-' }.take(24)) }
    }

    fun onAccountHolderChanged(value: String) {
        _uiState.update { it.copy(accountHolderInput = value.take(100)) }
    }

    fun saveSettings() {
        val state = _uiState.value
        if (state.isBusy) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = paymentsRepository.updateSettings(state.transferAccountInput, state.accountHolderInput)) {
                is Result.Success -> _uiState.update {
                    it.copy(isBusy = false, settings = result.data,
                        transferAccountInput = result.data.transferAccount.orEmpty(),
                        accountHolderInput = result.data.accountHolder.orEmpty(),
                        message = "Cuenta Transfermóvil actualizada.")
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo guardar la cuenta")
                }
            }
        }
    }

    fun onProductPriceChanged(code: String, value: String) {
        val cleaned = value.filter { it.isDigit() || it == '.' || it == ',' }.take(20)
        _uiState.update { it.copy(productPriceInputs = it.productPriceInputs + (code to cleaned)) }
    }

    fun saveProductPrice(product: MonetizationProduct) {
        val raw = _uiState.value.productPriceInputs[product.code].orEmpty().replace(',', '.')
        val amount = raw.toBigDecimalOrNull()
        if (amount == null || amount <= BigDecimal.ZERO) {
            _uiState.update { it.copy(errorMessage = "La tarifa debe ser mayor que cero CUP") }
            return
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null, message = null) }
        viewModelScope.launch {
            when (val result = paymentsRepository.updateProductPrice(product.code, amount, true)) {
                is Result.Success -> {
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            products = state.products.map { if (it.code == result.data.code) result.data else it },
                            productPriceInputs = state.productPriceInputs +
                                (result.data.code to result.data.amount.stripTrailingZeros().toPlainString()),
                            message = "Tarifa de ${result.data.title} actualizada."
                        )
                    }
                }
                is Result.Error -> _uiState.update {
                    it.copy(isBusy = false, errorMessage = result.message ?: "No se pudo actualizar la tarifa")
                }
            }
        }
    }

    private suspend fun reloadQueue() {
        when (val result = paymentsRepository.reviewQueue()) {
            is Result.Success -> _uiState.update { it.copy(queue = result.data) }
            is Result.Error -> _uiState.update {
                it.copy(errorMessage = result.message ?: "La cola no se pudo actualizar")
            }
        }
    }
}
