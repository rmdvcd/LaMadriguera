package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.IdentityVerificationRow
import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.domain.repository.IdentityVerificationsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseIdentityVerificationsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<IdentityVerification, IdentityVerificationRow>(
        client = client,
        table = "identity_verifications",
        primaryKey = "id",
        serializer = IdentityVerificationRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "id", "user_id", "doc_type", "doc_ref", "full_name", "birthdate", "retention_until"
        ),
        updateColumns = emptySet()
    ), IdentityVerificationsRepository
