package com.lamadriguera.app.feature.chat.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt del feature Chat.
 * Mensajería en tiempo real mediante Supabase Realtime.
 *
 * Fase 2: bindear ChatRepository y proveer helpers de canales Realtime.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule
