package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.data.remote.supabase.row.PaymentRow
import java.time.Instant

fun PaymentRow.toDomain(): Payment = Payment(
    id = id,
    userId = userId,
    productType = productType,
    targetId = targetId,
    amount = amount,
    method = method,
    proofRef = proofRef,
    code = code,
    status = status,
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.let(Instant::parse)
)

fun Payment.toRow(): PaymentRow = PaymentRow(
    id = id,
    userId = userId,
    productType = productType,
    targetId = targetId,
    amount = amount,
    method = method,
    proofRef = proofRef,
    code = code,
    status = status,
    confirmedBy = confirmedBy,
    confirmedAt = confirmedAt?.toString()
)
