package com.lamadriguera.app.feature.auction.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Módulo Hilt del feature Auction (subastas).
 * Fase 2: bindear AuctionRepository y casos de uso de pujas.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuctionModule
