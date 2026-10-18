package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.data.remote.supabase.row.ModerationReviewRow
import com.lamadriguera.app.domain.model.ModerationReview
import com.lamadriguera.app.domain.model.ModerationReviewStatus
import com.lamadriguera.app.domain.model.ModerationTargetType
import java.time.Instant

fun ModerationReviewRow.toDomain(): ModerationReview = ModerationReview(
    id = id,
    targetType = ModerationTargetType.fromWire(targetType),
    targetId = targetId,
    source = source,
    category = category,
    severity = severity,
    status = ModerationReviewStatus.fromWire(status),
    excerpt = excerpt,
    evidence = evidence,
    aiProvider = aiProvider,
    aiScores = aiScores,
    reportId = reportId,
    createdAt = Instant.parse(createdAt),
    handledBy = handledBy,
    handledAt = handledAt?.let(Instant::parse),
    resolution = resolution
)

fun ModerationReview.toRow(): ModerationReviewRow = ModerationReviewRow(
    id = id,
    targetType = targetType.wireValue,
    targetId = targetId,
    source = source,
    category = category,
    severity = severity,
    status = status.wireValue,
    excerpt = excerpt,
    evidence = evidence,
    aiProvider = aiProvider,
    aiScores = aiScores,
    reportId = reportId,
    createdAt = createdAt.toString(),
    handledBy = handledBy,
    handledAt = handledAt?.toString(),
    resolution = resolution
)
