package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.ReviewRow
import com.lamadriguera.app.domain.model.Review
import com.lamadriguera.app.domain.repository.ReviewsRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseReviewsRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Review, ReviewRow>(
        client = client,
        table = "reviews",
        primaryKey = "id",
        serializer = ReviewRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "reviewer_id", "reviewee_id", "conversation_id", "rating", "text"),
        updateColumns = emptySet()
    ), ReviewsRepository
