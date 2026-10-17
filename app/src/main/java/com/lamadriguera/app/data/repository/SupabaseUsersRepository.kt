package com.lamadriguera.app.data.repository

import com.lamadriguera.app.data.mapper.toDomain
import com.lamadriguera.app.data.mapper.toRow
import com.lamadriguera.app.data.remote.supabase.row.UserRow
import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.domain.repository.UsersRepository
import io.github.jan.supabase.SupabaseClient
import javax.inject.Inject

class SupabaseUsersRepository @Inject constructor(client: SupabaseClient) :
    SupabaseTableRepository<User, UserRow>(
        client = client,
        table = "users",
        primaryKey = "id",
        serializer = UserRow.serializer(),
        toDomain = { it.toDomain() },
        toRow = { it.toRow() },
        insertColumns = setOf("id", "phone", "verification_level", "status"),
        updateColumns = setOf("phone")
    ), UsersRepository
