package com.lamadriguera.app.feature.auction.di

import com.lamadriguera.app.data.repository.SupabaseAuctionRepository
import com.lamadriguera.app.domain.repository.AuctionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Módulo Hilt del feature Auction (subastas).
 * Enlaza [AuctionRepository] con [SupabaseAuctionRepository].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuctionModule {

    @Binds
    @Singleton
    abstract fun bindAuctionRepository(impl: SupabaseAuctionRepository): AuctionRepository
}
