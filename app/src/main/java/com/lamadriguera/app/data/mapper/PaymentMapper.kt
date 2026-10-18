package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.MonetizationProductRow
import com.lamadriguera.app.data.remote.supabase.row.MonetizationSettingsRow
import com.lamadriguera.app.data.remote.supabase.row.PaymentReviewQueueRow
import com.lamadriguera.app.data.remote.supabase.row.PaymentRow
import com.lamadriguera.app.domain.model.MonetizationProduct
import com.lamadriguera.app.domain.model.MonetizationSettings
import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.domain.model.PaymentReviewItem
import com.lamadriguera.app.domain.model.PromotionScope
import java.time.Instant

fun PaymentRow.toDomain(): Payment = Payment(
    id = id,
    userId = userId,
    productType = productType,
    targetId = targetId,
    amount = amount,
    currency = currency,
    method = method,
    proofRef = proofRef,
    code = code,
    status = status,
    productCode = productCode,
    productTier = productTier,
    createdAt = Instant.parse(createdAt),
    expiresAt = expiresAt?.let(Instant::parse),
    transferLast4 = transferLast4,
    transferAt = transferAt?.let(Instant::parse),
    submittedAt = submittedAt?.let(Instant::parse),
    receiptSha256 = receiptSha256,
    rejectionReason = rejectionReason,
    listingStatusBeforeReview = listingStatusBeforeReview,
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.let(Instant::parse),
    reviewedBy = reviewedBy,
    reviewedAt = reviewedAt?.let(Instant::parse),
    reconciliationAmount = reconciliationAmount,
    reconciliationTransferAt = reconciliationTransferAt?.let(Instant::parse),
    reconciliationLast4 = reconciliationLast4,
    reconciliationReference = reconciliationReference
)

fun Payment.toRow(): PaymentRow = PaymentRow(
    id = id,
    userId = userId,
    productType = productType,
    targetId = targetId,
    amount = amount,
    currency = currency,
    method = method,
    proofRef = proofRef,
    code = code,
    status = status,
    productCode = productCode,
    productTier = productTier,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt?.toString(),
    transferLast4 = transferLast4,
    transferAt = transferAt?.toString(),
    submittedAt = submittedAt?.toString(),
    receiptSha256 = receiptSha256,
    rejectionReason = rejectionReason,
    listingStatusBeforeReview = listingStatusBeforeReview,
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.toString(),
    reviewedBy = reviewedBy,
    reviewedAt = reviewedAt?.toString(),
    reconciliationAmount = reconciliationAmount,
    reconciliationTransferAt = reconciliationTransferAt?.toString(),
    reconciliationLast4 = reconciliationLast4,
    reconciliationReference = reconciliationReference
)

fun MonetizationProductRow.toDomain(): MonetizationProduct = MonetizationProduct(
    code = code,
    productType = productType,
    tier = tier,
    title = title,
    description = description,
    amount = amount,
    currency = currency,
    durationHours = durationHours,
    reachScope = reachScope?.let { runCatching { PromotionScope.valueOf(it.uppercase()) }.getOrNull() },
    requiresMunicipality = requiresMunicipality,
    isActive = isActive,
    sortOrder = sortOrder
)

fun MonetizationSettingsRow.toDomain(): MonetizationSettings = MonetizationSettings(
    transferAccount = transferAccount,
    accountHolder = accountHolder,
    paymentWindowHours = paymentWindowHours
)

fun PaymentReviewQueueRow.toDomain(): PaymentReviewItem = PaymentReviewItem(
    payment = Payment(
        id = paymentId,
        userId = userId,
        productType = productType,
        targetId = targetId,
        amount = amount,
        currency = currency,
        code = code,
        status = status,
        productCode = productCode,
        productTier = productTier,
        proofRef = proofRef,
        receiptSha256 = receiptSha256,
        transferLast4 = transferLast4,
        transferAt = Instant.parse(transferAt),
        submittedAt = Instant.parse(submittedAt),
        createdAt = Instant.parse(createdAt),
        expiresAt = Instant.parse(expiresAt)
    ),
    productTitle = productTitle,
    listingTitle = listingTitle,
    ownerName = ownerName,
    ownerPhone = ownerPhone
)
