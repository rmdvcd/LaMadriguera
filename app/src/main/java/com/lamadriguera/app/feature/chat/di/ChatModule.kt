package com.lamadriguera.app.feature.chat.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt del feature Chat (diseño C).
 *
 * El contrato `ChatRepository` está bindeado en
 * [com.lamadriguera.app.core.di.RepositoryModule] a
 * `SupabaseChatRepository`; los primitivos criptográficos (AES-256-GCM,
 * X25519, envolturas, cofre local) se proveen desde
 * [com.lamadriguera.app.core.di.SecurityModule].
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule
