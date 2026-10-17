package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.PaymentRow
import com.lamadriguera.app.domain.model.Payment
import com.lamadriguera.app.domain.repository.PaymentsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabasePaymentsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Payment, PaymentRow>(
        client = client,
        table = "payments",
        primaryKey = "id",
        serializer = PaymentRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "user_id", "product_type", "target_id", "amount", "method", "proof_ref", "code"
        ),
        updateColumns = emptySet()
    ), PaymentsRepository
