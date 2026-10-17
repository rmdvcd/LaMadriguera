package com.lamadriguera.app.core.di

import com.lamadriguera.app.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

/**
 * Cliente único de Supabase para toda la app.
 *
 * Las credenciales se inyectan vía BuildConfig desde `local.properties`
 * (SUPABASE_URL, SUPABASE_ANON_KEY). Si faltan, se usan placeholders para
 * permitir que el esqueleto compile y arranque sin secretos.
 *
 * Solo la capa `data` debe consumir este cliente; nunca los Composables.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        // Guarda y restaura la sesión automáticamente (SettingsSessionManager)
        install(Auth) {
            autoLoadFromStorage = true
            autoSaveToStorage = true
            alwaysAutoRefresh = true
        }
        install(Postgrest)
        install(Storage)
        install(Realtime)
    }
}
