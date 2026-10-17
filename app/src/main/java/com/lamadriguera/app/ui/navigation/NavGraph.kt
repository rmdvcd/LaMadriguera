package com.lamadriguera.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lamadriguera.app.R
import com.lamadriguera.app.ui.components.LaMadrigueraBottomBar
import com.lamadriguera.app.ui.components.LaMadrigueraTopBar

/**
 * Grafo de navegación de La Madriguera.
 *
 * Este esqueleto declara todas las rutas; cada destino renderiza un placeholder.
 *
 * Mientras no exista autenticación, el grafo arranca en [Destinations.Listings]
 * para poder ver el shell (top bar + bottom bar). En fase 1 el arranque pasará a
 * [Destinations.Splash], que resolverá la sesión con AuthRepository y navegará a
 * Login o Home según corresponda.
 *
 * Ningún Composable llama a Supabase directamente.
 */
@Composable
fun LaMadrigueraNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: Destinations = Destinations.Listings
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showChrome = currentDestination != null &&
        currentDestination.hierarchy.none { it.isAuthOrSplash() }

    val showBottomBar = showChrome &&
        currentDestination?.hierarchy?.any { it.isBottomTab() } == true

    Scaffold(
        topBar = {
            if (showChrome) {
                LaMadrigueraTopBar(title = stringResource(R.string.app_name))
            }
        },
        bottomBar = {
            if (showBottomBar) {
                LaMadrigueraBottomBar(
                    navController = navController,
                    currentDestination = currentDestination
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Arranque y autenticación
            composable<Destinations.Splash> {
                PlaceholderScreen(R.string.screen_splash)
            }
            composable<Destinations.Login> {
                PlaceholderScreen(R.string.auth_login)
            }
            composable<Destinations.Register> {
                PlaceholderScreen(R.string.auth_register)
            }
            composable<Destinations.ForgotPassword> {
                PlaceholderScreen(R.string.auth_forgot_password)
            }

            // Raíz autenticada
            composable<Destinations.Home> {
                PlaceholderScreen(R.string.nav_home)
            }

            // Marketplace
            composable<Destinations.Listings> {
                PlaceholderScreen(R.string.nav_listings)
            }
            composable<Destinations.ListingDetail> {
                PlaceholderScreen(R.string.screen_listing_detail)
            }
            composable<Destinations.CreateListing> {
                PlaceholderScreen(R.string.listings_create)
            }
            composable<Destinations.EditListing> {
                PlaceholderScreen(R.string.screen_edit_listing)
            }

            // Subastas
            composable<Destinations.Auctions> {
                PlaceholderScreen(R.string.nav_auction)
            }
            composable<Destinations.AuctionDetail> {
                PlaceholderScreen(R.string.screen_auction_detail)
            }
            composable<Destinations.CreateAuction> {
                PlaceholderScreen(R.string.screen_create_auction)
            }

            // Chat
            composable<Destinations.Conversations> {
                PlaceholderScreen(R.string.nav_chat)
            }
            composable<Destinations.ChatThread> {
                PlaceholderScreen(R.string.screen_chat_thread)
            }

            // Apuestas
            composable<Destinations.BettingHub> {
                PlaceholderScreen(R.string.nav_betting)
            }
            composable<Destinations.BetDetail> {
                PlaceholderScreen(R.string.screen_bet_detail)
            }
            composable<Destinations.CreateBet> {
                PlaceholderScreen(R.string.screen_create_bet)
            }
            composable<Destinations.MyBets> {
                PlaceholderScreen(R.string.betting_my_bets)
            }

            // Perfil
            composable<Destinations.Profile> {
                PlaceholderScreen(R.string.nav_profile)
            }
            composable<Destinations.EditProfile> {
                PlaceholderScreen(R.string.profile_edit)
            }
            composable<Destinations.Settings> {
                PlaceholderScreen(R.string.profile_settings)
            }

            // Pagos
            composable<Destinations.Payments> {
                PlaceholderScreen(R.string.payments_title)
            }
            composable<Destinations.Deposit> {
                PlaceholderScreen(R.string.payments_deposit)
            }
            composable<Destinations.Withdraw> {
                PlaceholderScreen(R.string.payments_withdraw)
            }
            composable<Destinations.TransactionHistory> {
                PlaceholderScreen(R.string.screen_transactions)
            }

            // Moderación
            composable<Destinations.Moderation> {
                PlaceholderScreen(R.string.moderation_title)
            }
            composable<Destinations.Reports> {
                PlaceholderScreen(R.string.moderation_reports)
            }
            composable<Destinations.ReportDetail> {
                PlaceholderScreen(R.string.screen_report_detail)
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(@StringRes titleRes: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.placeholder_screen, stringResource(titleRes)),
            textAlign = TextAlign.Center
        )
    }
}

private fun NavDestination.isAuthOrSplash(): Boolean =
    hasRoute<Destinations.Splash>() ||
        hasRoute<Destinations.Login>() ||
        hasRoute<Destinations.Register>() ||
        hasRoute<Destinations.ForgotPassword>()

private fun NavDestination.isBottomTab(): Boolean =
    hasRoute<Destinations.Listings>() ||
        hasRoute<Destinations.Auctions>() ||
        hasRoute<Destinations.Conversations>() ||
        hasRoute<Destinations.BettingHub>() ||
        hasRoute<Destinations.Profile>()
