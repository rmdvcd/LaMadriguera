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
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.feature.auction.AuctionConfigScreen
import com.lamadriguera.app.feature.auction.AuctionConfigViewModel
import com.lamadriguera.app.feature.auction.BidHistoryScreen
import com.lamadriguera.app.feature.auction.BidHistoryViewModel
import com.lamadriguera.app.feature.auth.IdentityVerificationScreen
import com.lamadriguera.app.feature.auth.IdentityVerificationViewModel
import com.lamadriguera.app.feature.auth.PhoneLoginScreen
import com.lamadriguera.app.feature.auth.PhoneLoginViewModel
import com.lamadriguera.app.feature.auth.SplashScreen
import com.lamadriguera.app.feature.auth.SplashViewModel
import com.lamadriguera.app.feature.chat.ChatScreen
import com.lamadriguera.app.feature.chat.ChatViewModel
import com.lamadriguera.app.feature.chat.ConversationListScreen
import com.lamadriguera.app.feature.chat.ConversationListViewModel
import com.lamadriguera.app.feature.betting.BetDetailScreen
import com.lamadriguera.app.feature.betting.BetDetailViewModel
import com.lamadriguera.app.feature.betting.BettingGroupScreen
import com.lamadriguera.app.feature.betting.BettingGroupViewModel
import com.lamadriguera.app.feature.betting.CreateBetScreen
import com.lamadriguera.app.feature.betting.CreateBetViewModel
import com.lamadriguera.app.feature.listings.CreateListingScreen
import com.lamadriguera.app.feature.listings.CreateListingViewModel
import com.lamadriguera.app.feature.listings.ListingDetailScreen
import com.lamadriguera.app.feature.listings.ListingDetailViewModel
import com.lamadriguera.app.feature.listings.ListingsScreen
import com.lamadriguera.app.feature.listings.ListingsViewModel
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
 * PhoneLogin o Publicaciones.
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

            // Marketplace (Listings)
            composable<Destinations.Listings> {
                val viewModel: ListingsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ListingsScreen(
                    state = state,
                    onQueryChanged = viewModel::onQueryChanged,
                    onMunicipalitySelected = viewModel::onMunicipalitySelected,
                    onCategorySelected = viewModel::onCategorySelected,
                    onKindSelected = viewModel::onKindSelected,
                    onOpenListing = { id ->
                        navController.navigate(Destinations.ListingDetail(id))
                    },
                    onCreateListing = {
                        navController.navigate(Destinations.CreateListing)
                    },
                    onRetry = viewModel::refresh
                )
            }
            composable<Destinations.ListingDetail> {
                val viewModel: ListingDetailViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ListingDetailScreen(
                    state = state,
                    onBidAmountChanged = viewModel::onBidAmountChanged,
                    onPlaceBid = viewModel::placeBid,
                    onCloseAuction = viewModel::closeAuction,
                    onOpenBidHistory = { id ->
                        navController.navigate(Destinations.BidHistory(id))
                    },
                    onOpenAuctionConfig = { id ->
                        navController.navigate(Destinations.AuctionConfig(id))
                    },
                    onEditListing = { id ->
                        navController.navigate(Destinations.EditListing(id))
                    },
                    onOpenIdentityVerification = {
                        navController.navigate(Destinations.IdentityVerification)
                    },
                    onContactSeller = viewModel::openChat,
                    onRetry = viewModel::refresh
                )
                LaunchedEffect(state.chatConversationId) {
                    val conversationId = state.chatConversationId
                    if (conversationId != null) {
                        viewModel.onChatNavigated()
                        navController.navigate(Destinations.ChatThread(conversationId))
                    }
                }
            }
            composable<Destinations.CreateListing> {
                val viewModel: CreateListingViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CreateListingScreen(
                    state = state,
                    onKindChanged = viewModel::onKindChanged,
                    onTitleChanged = viewModel::onTitleChanged,
                    onDescriptionChanged = viewModel::onDescriptionChanged,
                    onCategorySelected = viewModel::onCategorySelected,
                    onPriceChanged = viewModel::onPriceChanged,
                    onPriceNegotiableChanged = viewModel::onPriceNegotiableChanged,
                    onMunicipalitySelected = viewModel::onMunicipalitySelected,
                    onRadiusChanged = viewModel::onRadiusChanged,
                    onPrivacyLevelChanged = viewModel::onPrivacyLevelChanged,
                    onVisibilityChanged = viewModel::onVisibilityChanged,
                    onPhotoSelected = viewModel::onPhotoSelected,
                    onPhotoRemoved = viewModel::onPhotoRemoved,
                    onAllowAuctionChanged = viewModel::onAllowAuctionChanged,
                    onOpenAuctionConfig = viewModel::openAuctionConfigScreen,
                    onCloseAuctionConfig = viewModel::closeAuctionConfigScreen,
                    onAuctionStartingPriceChanged = viewModel::onAuctionStartingPriceChanged,
                    onAuctionMinIncrementChanged = viewModel::onAuctionMinIncrementChanged,
                    onAuctionReservePriceChanged = viewModel::onAuctionReservePriceChanged,
                    onAuctionStartsAtChanged = viewModel::onAuctionStartsAtChanged,
                    onAuctionEndsAtChanged = viewModel::onAuctionEndsAtChanged,
                    onAuctionAutoExtendChanged = viewModel::onAuctionAutoExtendChanged,
                    onAuctionRequiresIdentityChanged = viewModel::onAuctionRequiresIdentityChanged,
                    onAuctionVisibleBidsChanged = viewModel::onAuctionVisibleBidsChanged,
                    onConfirmAuctionConfig = viewModel::confirmAuctionConfig,
                    onSubmit = viewModel::submit
                )
                LaunchedEffect(state.savedListing?.id) {
                    val savedId = state.savedListing?.id
                    if (savedId != null) {
                        navController.navigate(Destinations.ListingDetail(savedId)) {
                            popUpTo<Destinations.CreateListing> { inclusive = true }
                        }
                    }
                }
            }
            composable<Destinations.EditListing> {
                val viewModel: CreateListingViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CreateListingScreen(
                    state = state,
                    onKindChanged = viewModel::onKindChanged,
                    onTitleChanged = viewModel::onTitleChanged,
                    onDescriptionChanged = viewModel::onDescriptionChanged,
                    onCategorySelected = viewModel::onCategorySelected,
                    onPriceChanged = viewModel::onPriceChanged,
                    onPriceNegotiableChanged = viewModel::onPriceNegotiableChanged,
                    onMunicipalitySelected = viewModel::onMunicipalitySelected,
                    onRadiusChanged = viewModel::onRadiusChanged,
                    onPrivacyLevelChanged = viewModel::onPrivacyLevelChanged,
                    onVisibilityChanged = viewModel::onVisibilityChanged,
                    onPhotoSelected = viewModel::onPhotoSelected,
                    onPhotoRemoved = viewModel::onPhotoRemoved,
                    onAllowAuctionChanged = viewModel::onAllowAuctionChanged,
                    onOpenAuctionConfig = viewModel::openAuctionConfigScreen,
                    onCloseAuctionConfig = viewModel::closeAuctionConfigScreen,
                    onAuctionStartingPriceChanged = viewModel::onAuctionStartingPriceChanged,
                    onAuctionMinIncrementChanged = viewModel::onAuctionMinIncrementChanged,
                    onAuctionReservePriceChanged = viewModel::onAuctionReservePriceChanged,
                    onAuctionStartsAtChanged = viewModel::onAuctionStartsAtChanged,
                    onAuctionEndsAtChanged = viewModel::onAuctionEndsAtChanged,
                    onAuctionAutoExtendChanged = viewModel::onAuctionAutoExtendChanged,
                    onAuctionRequiresIdentityChanged = viewModel::onAuctionRequiresIdentityChanged,
                    onAuctionVisibleBidsChanged = viewModel::onAuctionVisibleBidsChanged,
                    onConfirmAuctionConfig = viewModel::confirmAuctionConfig,
                    onSubmit = viewModel::submit
                )
                LaunchedEffect(state.savedListing?.id) {
                    val savedId = state.savedListing?.id
                    if (savedId != null) {
                        navController.popBackStack()
                    }
                }
            }

            // Subastas
            composable<Destinations.Auctions> {
                val viewModel: ListingsViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    if (state.selectedSaleMode != SaleMode.AUCTION) {
                        viewModel.onSaleModeSelected(SaleMode.AUCTION)
                    }
                }
                ListingsScreen(
                    state = state,
                    onQueryChanged = viewModel::onQueryChanged,
                    onMunicipalitySelected = viewModel::onMunicipalitySelected,
                    onCategorySelected = viewModel::onCategorySelected,
                    onKindSelected = viewModel::onKindSelected,
                    onOpenListing = { id ->
                        navController.navigate(Destinations.ListingDetail(id))
                    },
                    onCreateListing = {
                        navController.navigate(Destinations.CreateListing)
                    },
                    onRetry = viewModel::refresh
                )
            }
            composable<Destinations.AuctionDetail> {
                val viewModel: ListingDetailViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ListingDetailScreen(
                    state = state,
                    onBidAmountChanged = viewModel::onBidAmountChanged,
                    onPlaceBid = viewModel::placeBid,
                    onCloseAuction = viewModel::closeAuction,
                    onOpenBidHistory = { id ->
                        navController.navigate(Destinations.BidHistory(id))
                    },
                    onOpenAuctionConfig = { id ->
                        navController.navigate(Destinations.AuctionConfig(id))
                    },
                    onEditListing = { id ->
                        navController.navigate(Destinations.EditListing(id))
                    },
                    onOpenIdentityVerification = {
                        navController.navigate(Destinations.IdentityVerification)
                    },
                    onContactSeller = viewModel::openChat,
                    onRetry = viewModel::refresh
                )
                LaunchedEffect(state.chatConversationId) {
                    val conversationId = state.chatConversationId
                    if (conversationId != null) {
                        viewModel.onChatNavigated()
                        navController.navigate(Destinations.ChatThread(conversationId))
                    }
                }
            }
            composable<Destinations.CreateAuction> {
                val viewModel: CreateListingViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    if (!state.allowAuction) {
                        viewModel.onAllowAuctionChanged(true)
                    }
                }
                CreateListingScreen(
                    state = state,
                    onKindChanged = viewModel::onKindChanged,
                    onTitleChanged = viewModel::onTitleChanged,
                    onDescriptionChanged = viewModel::onDescriptionChanged,
                    onCategorySelected = viewModel::onCategorySelected,
                    onPriceChanged = viewModel::onPriceChanged,
                    onPriceNegotiableChanged = viewModel::onPriceNegotiableChanged,
                    onMunicipalitySelected = viewModel::onMunicipalitySelected,
                    onRadiusChanged = viewModel::onRadiusChanged,
                    onPrivacyLevelChanged = viewModel::onPrivacyLevelChanged,
                    onVisibilityChanged = viewModel::onVisibilityChanged,
                    onPhotoSelected = viewModel::onPhotoSelected,
                    onPhotoRemoved = viewModel::onPhotoRemoved,
                    onAllowAuctionChanged = viewModel::onAllowAuctionChanged,
                    onOpenAuctionConfig = viewModel::openAuctionConfigScreen,
                    onCloseAuctionConfig = viewModel::closeAuctionConfigScreen,
                    onAuctionStartingPriceChanged = viewModel::onAuctionStartingPriceChanged,
                    onAuctionMinIncrementChanged = viewModel::onAuctionMinIncrementChanged,
                    onAuctionReservePriceChanged = viewModel::onAuctionReservePriceChanged,
                    onAuctionStartsAtChanged = viewModel::onAuctionStartsAtChanged,
                    onAuctionEndsAtChanged = viewModel::onAuctionEndsAtChanged,
                    onAuctionAutoExtendChanged = viewModel::onAuctionAutoExtendChanged,
                    onAuctionRequiresIdentityChanged = viewModel::onAuctionRequiresIdentityChanged,
                    onAuctionVisibleBidsChanged = viewModel::onAuctionVisibleBidsChanged,
                    onConfirmAuctionConfig = viewModel::confirmAuctionConfig,
                    onSubmit = viewModel::submit
                )
            }
            composable<Destinations.AuctionConfig> {
                val viewModel: AuctionConfigViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                AuctionConfigScreen(
                    state = state,
                    onStartingPriceChanged = viewModel::onStartingPriceChanged,
                    onMinIncrementChanged = viewModel::onMinIncrementChanged,
                    onReservePriceChanged = viewModel::onReservePriceChanged,
                    onStartsAtChanged = viewModel::onStartsAtChanged,
                    onEndsAtChanged = viewModel::onEndsAtChanged,
                    onAutoExtendMinutesChanged = viewModel::onAutoExtendMinutesChanged,
                    onRequiresIdentityChanged = viewModel::onRequiresIdentityChanged,
                    onVisibleBidsChanged = viewModel::onVisibleBidsChanged,
                    onSave = viewModel::saveConfiguration,
                    onCancel = { navController.popBackStack() }
                )
                LaunchedEffect(state.savedListing?.id) {
                    if (state.savedListing != null) {
                        navController.popBackStack()
                    }
                }
            }
            composable<Destinations.BidHistory> {
                val viewModel: BidHistoryViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                BidHistoryScreen(
                    state = state,
                    onRetry = viewModel::refresh
                )
            }

            // Chat (diseño C: cifrado E2E + escrow de disputa)
            composable<Destinations.Conversations> {
                val viewModel: ConversationListViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ConversationListScreen(
                    state = state,
                    onOpenConversation = { id ->
                        navController.navigate(Destinations.ChatThread(id))
                    },
                    onDismissNotice = viewModel::dismissNotifications,
                    onRetry = viewModel::refresh
                )
            }
            composable<Destinations.ChatThread> {
                val viewModel: ChatViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ChatScreen(
                    state = state,
                    onDraftChanged = viewModel::onDraftChanged,
                    onExpiryChanged = viewModel::onExpiryChanged,
                    onSendText = viewModel::onSendText,
                    onImagePicked = viewModel::onImagePicked,
                    onAudioPicked = viewModel::onAudioPicked,
                    onMediaPickFailed = viewModel::onMediaPickFailed,
                    onLocationRequestStarted = viewModel::onLocationRequestStarted,
                    onLocationAvailable = viewModel::onLocationAvailable,
                    onLocationFailed = viewModel::onLocationFailed,
                    onConfirmPersonalData = viewModel::confirmPersonalDataSend,
                    onDismissPersonalData = viewModel::dismissPersonalDataWarning,
                    onOpenReport = viewModel::openReportDialog,
                    onCloseReport = viewModel::closeReportDialog,
                    onReportReasonChanged = viewModel::onReportReasonChanged,
                    onSubmitReport = viewModel::submitReport,
                    onDismissReportBanner = viewModel::dismissReportBanner,
                    onDismissError = viewModel::dismissError
                )
            }

            // Apuestas P2P (el dinero nunca pasa por la aplicación)
            composable<Destinations.BettingHub> {
                val viewModel: BettingGroupViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                BettingGroupScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onCreateGroup = viewModel::createGroup,
                    onOpenGroup = { id -> navController.navigate(Destinations.BettingGroup(id)) },
                    onJoinGroup = viewModel::joinGroup,
                    onCreateBet = { id -> navController.navigate(Destinations.CreateBet(id)) },
                    onOpenBet = { id -> navController.navigate(Destinations.BetDetail(id)) },
                    onVerifyIdentity = { navController.navigate(Destinations.IdentityVerification) },
                    onInviteMember = viewModel::inviteMember,
                    onRemoveMember = viewModel::removeMember,
                    onDailyLimitChanged = viewModel::onDailyLimitInputChanged,
                    onSaveDailyLimit = viewModel::saveDailyLimit,
                    onSelfExclude = viewModel::selfExclude
                )
                LaunchedEffect(state.createdGroupId) {
                    state.createdGroupId?.let { id ->
                        viewModel.clearCreatedGroupId()
                        navController.navigate(Destinations.BettingGroup(id))
                    }
                }
            }
            composable<Destinations.BettingGroup> {
                val viewModel: BettingGroupViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                BettingGroupScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onCreateGroup = viewModel::createGroup,
                    onOpenGroup = { id -> navController.navigate(Destinations.BettingGroup(id)) },
                    onJoinGroup = viewModel::joinGroup,
                    onCreateBet = { id -> navController.navigate(Destinations.CreateBet(id)) },
                    onOpenBet = { id -> navController.navigate(Destinations.BetDetail(id)) },
                    onVerifyIdentity = { navController.navigate(Destinations.IdentityVerification) },
                    onInviteMember = viewModel::inviteMember,
                    onRemoveMember = viewModel::removeMember,
                    onDailyLimitChanged = viewModel::onDailyLimitInputChanged,
                    onSaveDailyLimit = viewModel::saveDailyLimit,
                    onSelfExclude = viewModel::selfExclude
                )
            }
            composable<Destinations.BetDetail> {
                val viewModel: BetDetailViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                BetDetailScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onAccept = viewModel::accept,
                    onSubmitResult = viewModel::submitResult,
                    onOpenDispute = viewModel::openDispute,
                    onResolveDispute = viewModel::resolveDispute,
                    onVerifyIdentity = { navController.navigate(Destinations.IdentityVerification) }
                )
            }
            composable<Destinations.CreateBet> {
                val viewModel: CreateBetViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                CreateBetScreen(
                    state = state,
                    onDescriptionChanged = viewModel::onDescriptionChanged,
                    onStakeChanged = viewModel::onStakeChanged,
                    onOddsChanged = viewModel::onOddsChanged,
                    onSubmit = viewModel::submit,
                    onVerifyIdentity = { navController.navigate(Destinations.IdentityVerification) },
                    onBackToGroup = { navController.popBackStack() }
                )
                LaunchedEffect(state.createdBetId) {
                    state.createdBetId?.let { id ->
                        navController.navigate(Destinations.BetDetail(id)) {
                            popUpTo<Destinations.CreateBet> { inclusive = true }
                        }
                    }
                }
            }
            composable<Destinations.MyBets> {
                val viewModel: BettingGroupViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                BettingGroupScreen(
                    state = state,
                    initialTab = 1,
                    onRefresh = viewModel::refresh,
                    onCreateGroup = viewModel::createGroup,
                    onOpenGroup = { id -> navController.navigate(Destinations.BettingGroup(id)) },
                    onJoinGroup = viewModel::joinGroup,
                    onCreateBet = { id -> navController.navigate(Destinations.CreateBet(id)) },
                    onOpenBet = { id -> navController.navigate(Destinations.BetDetail(id)) },
                    onVerifyIdentity = { navController.navigate(Destinations.IdentityVerification) },
                    onInviteMember = viewModel::inviteMember,
                    onRemoveMember = viewModel::removeMember,
                    onDailyLimitChanged = viewModel::onDailyLimitInputChanged,
                    onSaveDailyLimit = viewModel::saveDailyLimit,
                    onSelfExclude = viewModel::selfExclude
                )
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
    hasRoute<Destinations.AuctionConfig>() -> R.string.screen_auction_config
    hasRoute<Destinations.BidHistory>() -> R.string.screen_bid_history
    hasRoute<Destinations.Conversations>() -> R.string.nav_chat
    hasRoute<Destinations.ChatThread>() -> R.string.screen_chat_thread
    hasRoute<Destinations.BettingHub>() -> R.string.nav_betting
    hasRoute<Destinations.BettingGroup>() -> R.string.betting_group_screen
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
