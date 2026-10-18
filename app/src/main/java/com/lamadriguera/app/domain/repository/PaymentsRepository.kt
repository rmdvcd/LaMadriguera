package com.lamadriguera.app.domain.repository

import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.MonetizationSettings
import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.domain.model.PaymentReviewItem
import java.math.BigDecimal
import java.time.Instant

/**
 * Repositorio de órdenes de monetización.
 * Las transiciones sensibles (crear, enviar, confirmar, rechazar) se ejecutan
 * mediante RPC validadas por PostgreSQL; el cliente nunca actualiza el estado.
 */
interface PaymentsRepository : ReadRepository<Payment> {
    suspend fun listForUser(userId: String): Result<List<Payment>>
    suspend fun latestForListing(listingId: String): Result<Payment?>
    suspend fun listProducts(includeInactive: Boolean = false): Result<List<MonetizationProduct>>
    suspend fun getSettings(): Result<MonetizationSettings>
    suspend fun isPaymentAdmin(): Result<Boolean>

    suspend fun createOrder(listingId: String, productCode: String): Result<Payment>
    suspend fun submitProof(
        paymentId: String,
        imageBytes: ByteArray,
        transferLast4: String,
        transferAt: Instant
    ): Result<Payment>

    suspend fun reviewQueue(): Result<List<PaymentReviewItem>>
    suspend fun proofSignedUrl(proofRef: String): Result<String>
    suspend fun confirmPayment(
        paymentId: String,
        receivedAmount: BigDecimal,
        transferAt: Instant,
        transferLast4: String,
        reference: String?
    ): Result<Payment>

    suspend fun rejectPayment(paymentId: String, reason: String, blacklistReceipt: Boolean): Result<Payment>
    suspend fun updateSettings(transferAccount: String, accountHolder: String?): Result<MonetizationSettings>
    suspend fun updateProductPrice(code: String, amount: BigDecimal, isActive: Boolean): Result<MonetizationProduct>
}
