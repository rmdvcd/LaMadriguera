package com.lamadriguera.app.data.remote.supabase

import kotlinx.serialization.json.Json

/** Include defaults/nulls for explicit patches, but tolerate added server response columns. */
val SupabaseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
