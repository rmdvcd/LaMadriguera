package com.lamadriguera.app.ui.navigation

import androidx.annotation.StringRes
import com.lamadriguera.app.R
import kotlinx.serialization.Serializable

/**
 * Rutas tipadas (Navigation Compose + kotlinx.serialization).
 * Toda navegación debe usar estos destinos, nunca strings sueltos.
 *
 * Flujo:
 *  Splash -> (sesión válida?) -> Listings  |  PhoneLogin (teléfono + OTP)
 */
sealed interface Destinations {

    // --- Arranque y autenticación ---
    @Serializable data object Splash : Destinations
    @Serializable data object PhoneLogin : Destinations

    // --- Raíz autenticada ---
    @Serializable data object Home : Destinations

    // --- Marketplace ---
    @Serializable data object Listings : Destinations
    @Serializable data class ListingDetail(val listingId: String) : Destinations
    @Serializable data object CreateListing : Destinations
    @Serializable data class EditListing(val listingId: String) : Destinations

    // --- Subastas ---
    @Serializable data object Auctions : Destinations
    @Serializable data class AuctionDetail(val auctionId: String) : Destinations
    @Serializable data object CreateAuction : Destinations

    // --- Chat ---
    @Serializable data object Conversations : Destinations
    @Serializable data class ChatThread(val conversationId: String) : Destinations

    // --- Apuestas ---
    @Serializable data object BettingHub : Destinations
    @Serializable data class BetDetail(val betId: String) : Destinations
    @Serializable data object CreateBet : Destinations
    @Serializable data object MyBets : Destinations

    // --- Perfil ---
    @Serializable data object Profile : Destinations
    @Serializable data object EditProfile : Destinations
    @Serializable data object Settings : Destinations
    @Serializable data object IdentityVerification : Destinations
    @Serializable data object PrivacySettings : Destinations

    // --- Pagos ---
    @Serializable data object Payments : Destinations
    @Serializable data object Deposit : Destinations
    @Serializable data object Withdraw : Destinations
    @Serializable data object TransactionHistory : Destinations

    // --- Moderación ---
    @Serializable data object Moderation : Destinations
    @Serializable data object Reports : Destinations
    @Serializable data class ReportDetail(val reportId: String) : Destinations
}

/**
 * Tabs principales de la barra inferior.
 */
enum class BottomTab(
    val route: Destinations,
    @StringRes val labelRes: Int
) {
    LISTINGS(Destinations.Listings, R.string.nav_listings),
    AUCTIONS(Destinations.Auctions, R.string.nav_auction),
    CHAT(Destinations.Conversations, R.string.nav_chat),
    BETTING(Destinations.BettingHub, R.string.nav_betting),
    PROFILE(Destinations.Profile, R.string.nav_profile)
}
