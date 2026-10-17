package com.lamadriguera.app.data.remote.supabase.row

import com.lamadriguera.app.domain.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** PostgREST wire format. bytea uses PostgreSQL hex; timestamps use ISO-8601. */
@Serializable
data class CategoryRow(
    val id: String,
    val name: String,
    @SerialName("parent_id")
    val parentId: String? = null,
    @SerialName("is_custom")
    val isCustom: Boolean = false,
    @SerialName("created_by")
    val createdBy: String? = null
)
