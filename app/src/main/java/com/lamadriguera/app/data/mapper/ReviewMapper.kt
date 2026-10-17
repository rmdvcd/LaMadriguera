package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Review
import com.lamadriguera.app.data.remote.supabase.row.ReviewRow
import java.time.Instant

fun ReviewRow.toDomain(): Review = Review(
    id = id,
    reviewerId = reviewerId,
    revieweeId = revieweeId,
    conversationId = conversationId,
    rating = rating,
    text = text,
    status = status,
    createdAt = Instant.parse(createdAt)
)

fun Review.toRow(): ReviewRow = ReviewRow(
    id = id,
    reviewerId = reviewerId,
    revieweeId = revieweeId,
    conversationId = conversationId,
    rating = rating,
    text = text,
    status = status,
    createdAt = createdAt.toString()
)
