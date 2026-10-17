package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.IdentityVerification
import com.lamadriguera.app.data.remote.supabase.row.IdentityVerificationRow
import java.time.Instant
import java.time.LocalDate

fun IdentityVerificationRow.toDomain(): IdentityVerification = IdentityVerification(
    id = id,
    userId = userId,
    docType = docType,
    docRef = docRef.decodeBytea(),
    fullName = fullName,
    birthdate = LocalDate.parse(birthdate),
    status = status,
    verifiedBy = verifiedBy,
    verifiedAt = verifiedAt?.let(Instant::parse),
    retentionUntil = Instant.parse(retentionUntil)
)

fun IdentityVerification.toRow(): IdentityVerificationRow = IdentityVerificationRow(
    id = id,
    userId = userId,
    docType = docType,
    docRef = docRef.encodeBytea(),
    fullName = fullName,
    birthdate = birthdate.toString(),
    status = status,
    verifiedBy = verifiedBy,
    verifiedAt = verifiedAt?.toString(),
    retentionUntil = retentionUntil.toString()
)
