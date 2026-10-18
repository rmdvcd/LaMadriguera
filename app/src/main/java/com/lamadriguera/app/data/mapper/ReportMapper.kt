package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Report
import com.lamadriguera.app.data.remote.supabase.row.ReportRow
import java.time.Instant

fun ReportRow.toDomain(): Report = Report(
    id = id,
    reporterId = reporterId,
    targetType = targetType,
    targetId = targetId,
    reason = reason,
    evidenceUrl = evidenceUrl,
    status = status,
    handledBy = handledBy,
    createdAt = Instant.parse(createdAt),
    category = category,
    severity = severity
)

fun Report.toRow(): ReportRow = ReportRow(
    id = id,
    reporterId = reporterId,
    targetType = targetType,
    targetId = targetId,
    reason = reason,
    evidenceUrl = evidenceUrl,
    status = status,
    handledBy = handledBy,
    createdAt = createdAt.toString(),
    category = category,
    severity = severity
)
