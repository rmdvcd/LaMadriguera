package com.lamadriguera.app.feature.betting.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt del feature Betting (apuestas peer-to-peer).
 * Los casos de uso de validación de saldo y creación de apuestas se añaden en fase 2.
 */
@Module
@InstallIn(SingletonComponent::class)
object BettingModule
