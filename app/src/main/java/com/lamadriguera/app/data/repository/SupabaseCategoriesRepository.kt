package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.CategoryRow
import com.lamadriguera.app.domain.model.Category
import com.lamadriguera.app.domain.repository.CategoriesRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseCategoriesRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Category, CategoryRow>(
        client = client,
        table = "categories",
        primaryKey = "id",
        serializer = CategoryRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "name", "parent_id", "is_custom", "created_by"),
        updateColumns = setOf("name", "parent_id")
    ), CategoriesRepository
