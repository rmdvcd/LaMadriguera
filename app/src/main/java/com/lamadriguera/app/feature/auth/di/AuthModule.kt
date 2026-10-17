package com.lamadriguera.app.feature.auth.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt del feature Auth.
 *
 * Fase 2:
 *  - @Binds AuthRepository -> AuthRepositoryImpl
 *  - @Provides AuthUseCases
 *
 * Regla de arquitectura: la UI nunca llama a Supabase directamente;
 * siempre ViewModel -> UseCase -> AuthRepository.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule
