package com.lamadriguera.app.core.di

import com.lamadriguera.app.data.repository.*
import com.lamadriguera.app.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindUsers(impl: SupabaseUsersRepository): UsersRepository

    @Binds @Singleton
    abstract fun bindIdentityVerifications(impl: SupabaseIdentityVerificationsRepository): IdentityVerificationsRepository

    @Binds @Singleton
    abstract fun bindProfiles(impl: SupabaseProfilesRepository): ProfilesRepository

    @Binds @Singleton
    abstract fun bindCategories(impl: SupabaseCategoriesRepository): CategoriesRepository

    @Binds @Singleton
    abstract fun bindListings(impl: SupabaseListingsRepository): ListingsRepository

    @Binds @Singleton
    abstract fun bindBids(impl: SupabaseBidsRepository): BidsRepository

    @Binds @Singleton
    abstract fun bindConversations(impl: SupabaseConversationsRepository): ConversationsRepository

    @Binds @Singleton
    abstract fun bindMessages(impl: SupabaseMessagesRepository): MessagesRepository

    @Binds @Singleton
    abstract fun bindChat(impl: SupabaseChatRepository): ChatRepository

    @Binds @Singleton
    abstract fun bindReviews(impl: SupabaseReviewsRepository): ReviewsRepository

    @Binds @Singleton
    abstract fun bindReports(impl: SupabaseReportsRepository): ReportsRepository

    @Binds @Singleton
    abstract fun bindPayments(impl: SupabasePaymentsRepository): PaymentsRepository

    @Binds @Singleton
    abstract fun bindBoosts(impl: SupabaseBoostsRepository): BoostsRepository

    @Binds @Singleton
    abstract fun bindBettingGroups(impl: SupabaseBettingGroupsRepository): BettingGroupsRepository

    @Binds @Singleton
    abstract fun bindBettingGroupMembers(impl: SupabaseBettingGroupMembersRepository): BettingGroupMembersRepository

    @Binds @Singleton
    abstract fun bindBets(impl: SupabaseBetsRepository): BetsRepository

    @Binds @Singleton
    abstract fun bindBetting(impl: SupabaseBettingRepository): BettingRepository

    @Binds @Singleton
    abstract fun bindModerationActions(impl: SupabaseModerationActionsRepository): ModerationActionsRepository

    @Binds @Singleton
    abstract fun bindNotifications(impl: SupabaseNotificationsRepository): NotificationsRepository

}
