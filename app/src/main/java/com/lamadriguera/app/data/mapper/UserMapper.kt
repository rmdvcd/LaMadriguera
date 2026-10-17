package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.User
import com.lamadriguera.app.data.remote.supabase.row.UserRow
import java.time.Instant

fun UserRow.toDomain(): User = User(
    id = id,
    phone = phone,
    verificationLevel = verificationLevel,
    status = status,
    createdAt = Instant.parse(createdAt)
)

fun User.toRow(): UserRow = UserRow(
    id = id,
    phone = phone,
    verificationLevel = verificationLevel,
    status = status,
    createdAt = createdAt.toString()
)
