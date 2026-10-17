package com.lamadriguera.app.data.mapper

import com.lamadriguera.app.domain.model.Profile
import com.lamadriguera.app.data.remote.supabase.row.ProfileRow

fun ProfileRow.toDomain(): Profile = Profile(
    userId = userId,
    displayName = displayName,
    alias = alias,
    avatarUrl = avatarUrl,
    municipalityId = municipalityId,
    privacyLevel = privacyLevel,
    bio = bio,
    reputationAvg = reputationAvg,
    reviewsCount = reviewsCount
)

fun Profile.toRow(): ProfileRow = ProfileRow(
    userId = userId,
    displayName = displayName,
    alias = alias,
    avatarUrl = avatarUrl,
    municipalityId = municipalityId,
    privacyLevel = privacyLevel,
    bio = bio,
    reputationAvg = reputationAvg,
    reviewsCount = reviewsCount
)
