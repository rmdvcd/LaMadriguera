package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Category
import com.lamadriguera.app.data.remote.supabase.row.CategoryRow

fun CategoryRow.toDomain(): Category = Category(
    id = id,
    name = name,
    parentId = parentId,
    isCustom = isCustom,
    createdBy = createdBy
)

fun Category.toRow(): CategoryRow = CategoryRow(
    id = id,
    name = name,
    parentId = parentId,
    isCustom = isCustom,
    createdBy = createdBy
)
