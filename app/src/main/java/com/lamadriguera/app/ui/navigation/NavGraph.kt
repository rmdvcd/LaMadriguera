package com.lamadriguera.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lamadriguera.app.R
import com.lamadriguera.app.core.result.UiState
import com.lamadriguera.app.feature.auth.IdentityVerificationScreen
import com.lamadriguera.app.feature.auth.IdentityVerificationViewModel
import com.lamadriguera.app.feature.auth.PhoneLoginScreen
import com.lamadriguera.app.feature.auth.PhoneLoginViewModel
import com.lamadriguera.app.feature.auth.SplashScreen
import com.lamadriguera.app.feature.auth.SplashViewModel
import com.lamadriguera.app.feature.profile.PrivacySettingsScreen
import com.lamadriguera.app.feature.profile.PrivacySettingsViewModel
import com.lamadriguera.app.feature.profile.ProfileScreen
import com.lamadriguera.app.feature.profile.ProfileViewModel
import com.lamadriguera.app.ui.components.ErrorState
import com.lamadriguera.app.ui.components.LaMadrigueraBottomBar
import com.lamadriguera.app.ui.components.LaMadrigueraTopBar
import com.lamadriguera.app.ui.components.LoadingState

/**
 * Grafo de navegación de La Madriguera.
 *
 * El arranque pasa por [Destinations.Splash], que resuelve la sesión con
 * [com.lamadriguera.app.domain.repository.AuthRepository] y navega a
 * PhoneLogin o Publicaciones. Los flujos de autenticación, verificación de
 * identidad, perfil y privacidad están implementados; el resto de destinos
 * renderiza un placeholder.
 *
 * Ningún Composable llama a Supabase directamente.
 */
@Composable
fun LaMadrigueraNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: Destinations = Destinations.Splash
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showChrome = currentDestination != null &&
        currentDestination.hierarchy.none { it.isAuthOrSplash() }

    val showBottomBar = showChrome &&
        currentDestination?.hierarchy?.any { it.isBottomTab() } == true

    Scaffold(
        topBar = {
            if (showChrome && currentDestination != null) {
                LaMadrigueraTopBar(
                    title = stringResource(currentDestination.topBarTitleRes()),
                    onBack = if (currentDestination.hierarchy.any { it.isBottomTab() }) {
                        null
                    } else {
                        { navController.popBackStack() }
                    }
                )
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
                val viewModel: SplashViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                SplashScreen(
                    state = state,
                    onRetry = viewModel::restoreSession,
                    onAuthenticated = {
                        navController.navigate(Destinations.Listings) {
                            popUpTo<Destinations.Splash> { inclusive = true }
                        }
                    },
                    onUnauthenticated = {
                        navController.navigate(Destinations.PhoneLogin) {
                            popUpTo<Destinations.Splash> { inclusive = true }
                        }
                    }
                )
            }
            composable<Destinations.PhoneLogin> {
                val viewModel: PhoneLoginViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                PhoneLoginScreen(
                    state = state,
                    onPhoneChanged = viewModel::onPhoneChanged,
                    onCodeChanged = viewModel::onCodeChanged,
                    onSendCode = viewModel::sendCode,
                    onResendCode = viewModel::resendCode,
                    onVerifyCode = viewModel::verifyCode,
                    onAuthenticated = {
                        navController.navigate(Destinations.Listings) {
                            popUpTo<Destinations.PhoneLogin> { inclusive = true }
                        }
                    }
                )
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
                val viewModel: ProfileViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val extras by viewModel.extras.collectAsStateWithLifecycle()
                when (val value = state) {
                    is UiState.Success -> ProfileScreen(
                        session = value.data.session,
                        onEditProfile = { navController.navigate(Destinations.EditProfile) },
                        onOpenPrivacySettings = {
                            navController.navigate(Destinations.PrivacySettings)
                        },
                        onOpenIdentityVerification = {
                            navController.navigate(Destinations.IdentityVerification)
                        },
                        onLogout = viewModel::logout
                    )
                    is UiState.Error -> ErrorState(
                        message = value.message,
                        onRetry = viewModel::refresh,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    )
                    else -> LoadingState(modifier = Modifier.fillMaxSize())
                }
                LaunchedEffect(extras.loggedOut) {
                    if (extras.loggedOut) {
                        navController.navigate(Destinations.PhoneLogin) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = true
                            }
                        }
                    }
                }
            }
            composable<Destinations.EditProfile> {
                PlaceholderScreen(R.string.profile_edit)
            }
            composable<Destinations.Settings> {
                PlaceholderScreen(R.string.profile_settings)
            }
            composable<Destinations.IdentityVerification> {
                val viewModel: IdentityVerificationViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                IdentityVerificationScreen(
                    state = state,
                    onFullNameChanged = viewModel::onFullNameChanged,
                    onBirthdateChanged = viewModel::onBirthdateChanged,
                    onFrontImageSelected = viewModel::onFrontImageSelected,
                    onBackImageSelected = viewModel::onBackImageSelected,
                    onTermsAcceptedChanged = viewModel::onTermsAcceptedChanged,
                    onSubmit = viewModel::submit,
                    onRefresh = viewModel::refresh
                )
            }
            composable<Destinations.PrivacySettings> {
                val viewModel: PrivacySettingsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                PrivacySettingsScreen(
                    state = state,
                    onPrivacyLevelChanged = viewModel::onPrivacyLevelChanged,
                    onRevealPolicyChanged = viewModel::onRevealPolicyChanged,
                    onAliasChanged = viewModel::onAliasChanged,
                    onSave = viewModel::save
                )
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

@StringRes
private fun NavDestination.topBarTitleRes(): Int = when {
    hasRoute<Destinations.Home>() -> R.string.nav_home
    hasRoute<Destinations.Listings>() -> R.string.nav_listings
    hasRoute<Destinations.ListingDetail>() -> R.string.screen_listing_detail
    hasRoute<Destinations.CreateListing>() -> R.string.listings_create
    hasRoute<Destinations.EditListing>() -> R.string.screen_edit_listing
    hasRoute<Destinations.Auctions>() -> R.string.nav_auction
    hasRoute<Destinations.AuctionDetail>() -> R.string.screen_auction_detail
    hasRoute<Destinations.CreateAuction>() -> R.string.screen_create_auction
    hasRoute<Destinations.Conversations>() -> R.string.nav_chat
    hasRoute<Destinations.ChatThread>() -> R.string.screen_chat_thread
    hasRoute<Destinations.BettingHub>() -> R.string.nav_betting
    hasRoute<Destinations.BetDetail>() -> R.string.screen_bet_detail
    hasRoute<Destinations.CreateBet>() -> R.string.screen_create_bet
    hasRoute<Destinations.MyBets>() -> R.string.betting_my_bets
    hasRoute<Destinations.Profile>() -> R.string.profile_title
    hasRoute<Destinations.EditProfile>() -> R.string.profile_edit
    hasRoute<Destinations.Settings>() -> R.string.profile_settings
    hasRoute<Destinations.IdentityVerification>() -> R.string.identity_title
    hasRoute<Destinations.PrivacySettings>() -> R.string.privacy_title
    hasRoute<Destinations.Payments>() -> R.string.payments_title
    hasRoute<Destinations.Deposit>() -> R.string.payments_deposit
    hasRoute<Destinations.Withdraw>() -> R.string.payments_withdraw
    hasRoute<Destinations.TransactionHistory>() -> R.string.screen_transactions
    hasRoute<Destinations.Moderation>() -> R.string.moderation_title
    hasRoute<Destinations.Reports>() -> R.string.moderation_reports
    hasRoute<Destinations.ReportDetail>() -> R.string.screen_report_detail
    else -> R.string.app_name
}

private fun NavDestination.isAuthOrSplash(): Boolean =
    hasRoute<Destinations.Splash>() || hasRoute<Destinations.PhoneLogin>()

private fun NavDestination.isBottomTab(): Boolean =
    hasRoute<Destinations.Listings>() ||
        hasRoute<Destinations.Auctions>() ||
        hasRoute<Destinations.Conversations>() ||
        hasRoute<Destinations.BettingHub>() ||
        hasRoute<Destinations.Profile>()
