package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.ProfileRow
import com.lamadriguera.app.domain.model.Profile
import com.lamadriguera.app.domain.repository.ProfilesRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseProfilesRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<Profile, ProfileRow>(
        client = client,
        table = "profiles",
        primaryKey = "user_id",
        serializer = ProfileRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf(
            "user_id", "display_name", "alias", "avatar_url", "municipality_id", "privacy_level",
            "bio"
        ),
        updateColumns = setOf(
            "display_name", "alias", "avatar_url", "municipality_id", "privacy_level", "bio"
        )
    ), ProfilesRepository
