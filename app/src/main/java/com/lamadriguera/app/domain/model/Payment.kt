package com.lamadriguera.app.domain.model

import java.math.BigDecimal
import java.time.Instant

/** Pago manual por Transfermóvil para promocionar una publicación. */
data class Payment(
    val id: String,
    val userId: String,
    val productType: ProductType,
    val targetId: String,
    val amount: BigDecimal,
    val currency: String = "CUP",
    val method: String = "transfermovil",
    val proofRef: String? = null,
    val code: String? = null,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val productCode: String? = null,
    val productTier: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val expiresAt: Instant? = null,
    val transferLast4: String? = null,
    val transferAt: Instant? = null,
    val submittedAt: Instant? = null,
    val receiptSha256: String? = null,
    val rejectionReason: String? = null,
    val listingStatusBeforeReview: ListingStatus? = null,
    val confirmedBy: String? = null,
    val confirmedAt: Instant? = null,
    val reviewedBy: String? = null,
    val reviewedAt: Instant? = null,
    val reconciliationAmount: BigDecimal? = null,
    val reconciliationTransferAt: Instant? = null,
    val reconciliationLast4: String? = null,
    val reconciliationReference: String? = null
)

/** Producto publicado por el catálogo de monetización del servidor. */
data class MonetizationProduct(
    val code: String,
    val productType: ProductType,
    val tier: String,
    val title: String,
    val description: String,
    val amount: BigDecimal,
    val currency: String = "CUP",
    val durationHours: Int,
    val reachScope: PromotionScope? = null,
    val requiresMunicipality: Boolean = false,
    val isActive: Boolean = false,
    val sortOrder: Int = 0
)

/** Cuenta de destino visible a todos los usuarios autenticados. */
data class MonetizationSettings(
    val transferAccount: String? = null,
    val accountHolder: String? = null,
    val paymentWindowHours: Int = 24
) {
    val isTransferAccountConfigured: Boolean
        get() = !transferAccount.isNullOrBlank()
}

/** Fila enriquecida de la conciliación; se devuelve solo a un admin/cobrador. */
data class PaymentReviewItem(
    val payment: Payment,
    val productTitle: String,
    val listingTitle: String,
    val ownerName: String,
    val ownerPhone: String?
)

/** Ámbito geográfico de una promoción de alcance. */
enum class PromotionScope {
    MUNICIPALITY,
    PROVINCE,
    NATIONAL
}
