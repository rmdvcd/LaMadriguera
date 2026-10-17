package com.lamadriguera.app.feature.auth.di

import com.lamadriguera.app.data.local.datastore.DataStoreDeviceAccountRegistry
import com.lamadriguera.app.data.repository.SupabaseAuthRepository
import com.lamadriguera.app.domain.repository.AuthRepository
import com.lamadriguera.app.domain.repository.DeviceAccountRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt del feature Auth.
 *
 * Regla de arquitectura: la UI nunca llama a Supabase directamente;
 * siempre ViewModel -> AuthRepository (o casos de uso de dominio).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: SupabaseAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDeviceAccountRegistry(impl: DataStoreDeviceAccountRegistry): DeviceAccountRegistry
}
