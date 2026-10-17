package com.lamadriguera.app.domain.model

import java.time.Instant
import java.math.BigDecimal

/** Domain model; money uses exact decimal arithmetic, dates use java.time. */
data class Payment(
    val id: String,
    val userId: String,
    val productType: ProductType,
    val targetId: String,
    val amount: BigDecimal,
    val method: String = "transfermovil",
    val proofRef: String? = null,
    val code: String? = null,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val confirmedBy: String? = null,
    val confirmedAt: Instant? = null
)
