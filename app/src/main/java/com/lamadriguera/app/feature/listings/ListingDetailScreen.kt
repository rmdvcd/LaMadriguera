package com.lamadriguera.app.feature.listings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.AuctionState
import com.lamadriguera.app.domain.model.CubanMunicipalities
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingStatus
import com.lamadriguera.app.domain.model.ListingVisibility
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.feature.moderation.ReportButton
import com.lamadriguera.app.domain.model.ModerationTargetType
import com.lamadriguera.app.ui.components.ErrorState
import com.lamadriguera.app.ui.components.LoadingState
import com.lamadriguera.app.ui.components.ReputationStars
import java.math.BigDecimal
import java.time.Instant

/**
 * Pantalla de detalle de publicación (`ListingDetailScreen`).
 *
 * Muestra precio fijo o el panel completo de subasta (estado, contador de
 * tiempo restante, última puja, formulario de puja con validaciones de
 * seguridad, acceso a `AuctionConfigScreen` para el dueño sin pujas y acceso
 * a `BidHistoryScreen` únicamente si `visible_bids = 'all'`).
 */
@Composable
fun ListingDetailScreen(
    state: ListingDetailUiState,
    onBidAmountChanged: (String) -> Unit,
    onPlaceBid: () -> Unit,
    onCloseAuction: () -> Unit,
    onOpenBidHistory: (String) -> Unit,
    onOpenAuctionConfig: (String) -> Unit,
    onEditListing: (String) -> Unit,
    onPromoteListing: (String) -> Unit,
    onOpenIdentityVerification: () -> Unit,
    onContactSeller: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier.fillMaxSize())
        state.listing == null -> ErrorState(
            message = state.errorMessage ?: stringResource(R.string.common_error),
            onRetry = onRetry,
            modifier = modifier.fillMaxSize()
        )
        else -> {
            val listing = state.listing
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                if (listing.photoUrl != null) {
                    AsyncImage(
                        model = listing.photoUrl,
                        contentDescription = listing.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (listing.kind == ListingKind.OFFER) {
                                    stringResource(R.string.listings_kind_offer)
                                } else {
                                    stringResource(R.string.listings_kind_request)
                                }
                            )
                        }
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(state.categoryName) }
                    )
                    if (listing.boostedUntil?.isAfter(Instant.now()) == true) {
                        AssistChip(onClick = {}, label = { Text("Promocionado") })
                    }
                    if (listing.kind == ListingKind.REQUEST &&
                        listing.visibility == ListingVisibility.PROVIDERS_ONLY
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.listings_badge_providers_only)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = listing.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    ReportButton(
                        targetType = ModerationTargetType.LISTING,
                        targetId = listing.id
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Precio fijo vs Panel de Subasta
                if (listing.saleMode == SaleMode.FIXED) {
                    FixedPriceCard(
                        price = listing.price,
                        currency = listing.currency,
                        negotiable = listing.priceNegotiable
                    )
                } else {
                    AuctionCounterCard(
                        state = state,
                        onBidAmountChanged = onBidAmountChanged,
                        onPlaceBid = onPlaceBid,
                        onCloseAuction = onCloseAuction,
                        onOpenBidHistory = { onOpenBidHistory(listing.id) },
                        onOpenAuctionConfig = { onOpenAuctionConfig(listing.id) },
                        onOpenIdentityVerification = onOpenIdentityVerification
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = listing.description,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Ubicación y Anunciante
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val municipalityText = CubanMunicipalities.displayName(listing.municipalityId)
                        Text(
                            text = "$municipalityText · ${stringResource(R.string.listings_radius_format, listing.radiusKm)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val sellerLabel = if (listing.privacyLevel == PrivacyLevel.ANON) {
                            state.ownerProfile?.alias?.let { "@$it" }
                                ?: stringResource(R.string.privacy_level_anon)
                        } else {
                            state.ownerProfile?.displayName ?: "Anunciante"
                        }
                        Text(
                            text = sellerLabel,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ReputationStars(
                            rating = state.ownerProfile?.reputationAvg ?: BigDecimal.ZERO,
                            reviewsCount = state.ownerProfile?.reviewsCount ?: 0
                        )
                    }
                }

                if (state.isOwner) {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (listing.status == ListingStatus.PAUSED_PAYMENT) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Publicación pausada mientras el cobrador revisa el pago. No se ha eliminado.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onEditListing(listing.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.listings_edit))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onPromoteListing(listing.id) },
                            enabled = listing.status in setOf(
                                ListingStatus.ACTIVE,
                                ListingStatus.FIXED,
                                ListingStatus.AUCTION_SCHEDULED,
                                ListingStatus.AUCTION_LIVE
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Promocionar publicación")
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onContactSeller,
                        enabled = !state.isOpeningChat,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.listing_contact_seller))
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedPriceCard(
    price: BigDecimal,
    currency: String,
    negotiable: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.auction_state_fixed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            val text = if (negotiable) {
                stringResource(R.string.listings_price_negotiable)
            } else {
                stringResource(
                    R.string.listings_price_format,
                    price.stripTrailingZeros().toPlainString(),
                    currency
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun AuctionCounterCard(
    state: ListingDetailUiState,
    onBidAmountChanged: (String) -> Unit,
    onPlaceBid: () -> Unit,
    onCloseAuction: () -> Unit,
    onOpenBidHistory: () -> Unit,
    onOpenAuctionConfig: () -> Unit,
    onOpenIdentityVerification: () -> Unit
) {
    val listing = requireNotNull(state.listing)
    val config = state.auctionConfig
    val auction = state.auction
    val currency = listing.currency

    val stateLabel = when (state.auctionState) {
        AuctionState.FIXED -> stringResource(R.string.auction_state_fixed)
        AuctionState.AUCTION_SCHEDULED -> stringResource(R.string.auction_state_scheduled)
        AuctionState.AUCTION_LIVE -> stringResource(R.string.auction_state_live)
        AuctionState.AUCTION_ENDED -> stringResource(R.string.auction_state_ended)
        AuctionState.AWARDED -> stringResource(R.string.auction_state_awarded)
        AuctionState.NO_RESERVE_MET -> stringResource(R.string.auction_state_no_reserve_met)
        AuctionState.EXPIRED -> stringResource(R.string.auction_state_expired)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(stateLabel) }
                )
                if (config?.requiresIdentity == true) {
                    Text(
                        text = stringResource(R.string.auction_identity_required_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Última puja o precio inicial
            val currentBid = auction?.currentBid
            Text(
                text = if (currentBid != null) {
                    stringResource(R.string.auction_current_bid)
                } else {
                    stringResource(R.string.auction_starting_price)
                },
                style = MaterialTheme.typography.labelMedium
            )
            val displayAmount = (currentBid ?: config?.startingPrice ?: listing.price)
                .stripTrailingZeros().toPlainString()
            Text(
                text = "$displayAmount $currency",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            if (currentBid == null) {
                Text(
                    text = stringResource(R.string.auction_no_bids_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // Reserva si existe
            if (config?.reservePrice != null) {
                val reserveMet = currentBid != null && currentBid >= config.reservePrice
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (reserveMet) {
                        stringResource(R.string.auction_reserve_met)
                    } else {
                        stringResource(R.string.auction_reserve_not_met)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Contador de tiempo restante
            val countdownTitle = when (state.auctionState) {
                AuctionState.AUCTION_SCHEDULED -> stringResource(R.string.auction_starts_in)
                AuctionState.AUCTION_LIVE -> stringResource(R.string.auction_time_remaining)
                else -> stringResource(R.string.auction_ended_label)
            }
            Text(
                text = "$countdownTitle: ${formatRemainingTime(state.remainingSeconds)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            // Información de siguiente puja y límite de 3 pujas por usuario
            if (state.auctionState == AuctionState.AUCTION_LIVE && !state.isOwner) {
                val minNext = (auction?.minimumNextBid ?: config?.startingPrice ?: listing.price)
                    .stripTrailingZeros().toPlainString()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.auction_min_next_bid, minNext, currency),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        R.string.auction_user_bids_remaining,
                        auction?.userBidsCount ?: 0
                    ),
                    style = MaterialTheme.typography.bodySmall
                )

                if (state.userMissingRequiredIdentity) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.auction_identity_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpenIdentityVerification) {
                        Text(stringResource(R.string.profile_verify_identity))
                    }
                } else if (state.hasOpenDispute) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.auction_dispute_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (state.userReachedMaxBids) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.auction_max_bids_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.bidAmountInput,
                        onValueChange = onBidAmountChanged,
                        label = { Text(stringResource(R.string.auction_bid_amount_label, currency)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !state.isPlacingBid,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onPlaceBid,
                        enabled = state.canPlaceBid,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isPlacingBid) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.auction_place_bid_button))
                        }
                    }
                }
            }

            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.feedbackMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.feedbackMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Botón de cierre si la subasta terminó
            if (state.auctionState == AuctionState.AUCTION_ENDED) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onCloseAuction,
                    enabled = !state.isClosingAuction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.auction_close_now_button))
                }
            }

            // Botón para ver BidHistoryScreen SOLO si visible_bids == 'all'
            if (state.canOpenBidHistory) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenBidHistory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            R.string.auction_view_bid_history,
                            auction?.bidsCount ?: 0
                        )
                    )
                }
            }

            // Solo el owner puede editar la config mientras no haya pujas
            if (state.canEditAuctionConfig) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenAuctionConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.auction_edit_config_button))
                }
            }
        }
    }
}

internal fun formatRemainingTime(totalSeconds: Long): String {
    val clamped = totalSeconds.coerceAtLeast(0L)
    val days = clamped / 86_400L
    val hours = (clamped % 86_400L) / 3_600L
    val minutes = (clamped % 3_600L) / 60L
    val seconds = clamped % 60L
    return if (days > 0) {
        "%dd %02d:%02d:%02d".format(days, hours, minutes, seconds)
    } else {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
