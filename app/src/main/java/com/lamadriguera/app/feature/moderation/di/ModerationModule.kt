package com.lamadriguera.app.feature.moderation.di

import com.lamadriguera.app.data.moderation.NsfwJsImageScanner
import com.lamadriguera.app.data.remote.moderation.RemoteModerationApi
import com.lamadriguera.app.data.repository.SupabaseGroupMessagesRepository
import com.lamadriguera.app.data.repository.SupabaseModerationRepository
import com.lamadriguera.app.domain.repository.GroupMessagesRepository
import com.lamadriguera.app.domain.repository.ImageModerationScanner
import com.lamadriguera.app.domain.repository.ModerationApi
import com.lamadriguera.app.domain.repository.ModerationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt del feature Moderación:
 *  - [ModerationApi]: llamada a la API de moderación (endpoint por variable de
 *    entorno, proveedor OpenAI Moderation o Llama Guard en la Edge Function).
 *  - [ModerationRepository]: cola humana y sanciones.
 *  - [ImageModerationScanner]: NSFW.js en cliente antes de subir imágenes.
 *  - [GroupMessagesRepository]: muro de mensajes de grupos públicos.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ModerationModule {

    @Binds
    @Singleton
    abstract fun bindModerationApi(impl: RemoteModerationApi): ModerationApi

    @Binds
    @Singleton
    abstract fun bindModerationRepository(impl: SupabaseModerationRepository): ModerationRepository

    @Binds
    @Singleton
    abstract fun bindImageModerationScanner(impl: NsfwJsImageScanner): ImageModerationScanner

    @Binds
    @Singleton
    abstract fun bindGroupMessagesRepository(impl: SupabaseGroupMessagesRepository): GroupMessagesRepository
}
