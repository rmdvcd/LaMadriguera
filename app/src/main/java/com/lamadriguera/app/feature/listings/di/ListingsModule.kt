package com.lamadriguera.app.feature.listings.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt del feature Listings (marketplace).
 * Postgrest para datos, Storage para imágenes (renderizadas con Coil).
 *
 * Fase 2: bindear ListingsRepository y sus casos de uso.
 */
@Module
@InstallIn(SingletonComponent::class)
object ListingsModule
