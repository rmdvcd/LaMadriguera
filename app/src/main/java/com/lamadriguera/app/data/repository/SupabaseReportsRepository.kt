package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.ReportRow
import com.lamadriguera.app.domain.model.Report
import com.lamadriguera.app.domain.repository.ReportsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseReportsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Report, ReportRow>(
        client = client,
        table = "reports",
        primaryKey = "id",
        serializer = ReportRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "reporter_id", "target_type", "target_id", "reason", "evidence_url"),
        updateColumns = emptySet()
    ), ReportsRepository
