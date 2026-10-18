package com.lamadriguera.app.feature.listings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.CubanMunicipalities
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingVisibility
import com.lamadriguera.app.domain.model.RankedListing
import com.lamadriguera.app.domain.model.SaleMode
import com.lamadriguera.app.ui.components.EmptyState
import com.lamadriguera.app.ui.components.ErrorState
import com.lamadriguera.app.ui.components.LoadingState
import com.lamadriguera.app.ui.components.ReputationStars

/**
 * Pantalla de listado de publicaciones con búsqueda por texto, filtro por
 * municipio, filtro por categoría y selector offer/request, ordenado por el
 * algoritmo de ranking.
 */
@Composable
fun ListingsScreen(
    state: ListingsUiState,
    onQueryChanged: (String) -> Unit,
    onMunicipalitySelected: (Int?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onKindSelected: (ListingKind?) -> Unit,
    onOpenListing: (String) -> Unit,
    onCreateListing: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var municipalityMenuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateListing) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.listings_create)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                placeholder = { Text(stringResource(R.string.listings_search_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Filtro Offer / Request
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = state.selectedKind == null,
                    onClick = { onKindSelected(null) },
                    label = { Text(stringResource(R.string.listings_filter_all)) }
                )
                FilterChip(
                    selected = state.selectedKind == ListingKind.OFFER,
                    onClick = { onKindSelected(ListingKind.OFFER) },
                    label = { Text(stringResource(R.string.listings_filter_offers)) }
                )
                FilterChip(
                    selected = state.selectedKind == ListingKind.REQUEST,
                    onClick = { onKindSelected(ListingKind.REQUEST) },
                    label = { Text(stringResource(R.string.listings_filter_requests)) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Selectores de Municipio y Categoría
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { municipalityMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = state.selectedMunicipalityId
                            ?.let { CubanMunicipalities.findById(it)?.name ?: "Municipio $it" }
                            ?: stringResource(R.string.listings_filter_municipality_all)
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = municipalityMenuExpanded,
                        onDismissRequest = { municipalityMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.listings_filter_municipality_all)) },
                            onClick = {
                                municipalityMenuExpanded = false
                                onMunicipalitySelected(null)
                            }
                        )
                        state.municipalities.forEach { mun ->
                            DropdownMenuItem(
                                text = { Text("${mun.name} (${mun.province})") },
                                onClick = {
                                    municipalityMenuExpanded = false
                                    onMunicipalitySelected(mun.id)
                                }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { categoryMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val label = state.categories
                            .firstOrNull { it.id == state.selectedCategoryId }?.name
                            ?: stringResource(R.string.listings_filter_category_all)
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.listings_filter_category_all)) },
                            onClick = {
                                categoryMenuExpanded = false
                                onCategorySelected(null)
                            }
                        )
                        state.categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    categoryMenuExpanded = false
                                    onCategorySelected(category.id)
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize())
                state.errorMessage != null -> ErrorState(
                    message = state.errorMessage,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize()
                )
                state.items.isEmpty() -> EmptyState(
                    messageRes = R.string.listings_empty,
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items, key = { it.listing.id }) { item ->
                        RankedListingCard(
                            item = item,
                            onClick = { onOpenListing(item.listing.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankedListingCard(
    item: RankedListing,
    onClick: () -> Unit
) {
    val listing = item.listing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isBoostApplied) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (listing.photoUrl != null) {
                    AsyncImage(
                        model = listing.photoUrl,
                        contentDescription = listing.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = listing.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = listing.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val priceText = if (listing.priceNegotiable && listing.saleMode == SaleMode.FIXED) {
                        stringResource(R.string.listings_price_negotiable)
                    } else {
                        stringResource(
                            R.string.listings_price_format,
                            listing.price.stripTrailingZeros().toPlainString(),
                            listing.currency
                        )
                    }
                    Text(
                        text = priceText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AssistChip(
                    onClick = onClick,
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
                if (listing.saleMode == SaleMode.AUCTION) {
                    AssistChip(
                        onClick = onClick,
                        label = { Text(stringResource(R.string.listings_badge_auction)) }
                    )
                }
                if (listing.kind == ListingKind.REQUEST &&
                    listing.visibility == ListingVisibility.PROVIDERS_ONLY
                ) {
                    AssistChip(
                        onClick = onClick,
                        label = { Text(stringResource(R.string.listings_badge_providers_only)) }
                    )
                }
                if (item.isPromoted) {
                    AssistChip(
                        onClick = onClick,
                        label = { Text(stringResource(R.string.listings_badge_boosted)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val munLabel = CubanMunicipalities.findById(listing.municipalityId)?.name
                        ?: listing.municipalityId?.let { "Municipio $it" }
                        ?: stringResource(R.string.profile_municipio_none)
                    val catLabel = item.categoryName.ifBlank { "General" }
                    Text(
                        text = "$catLabel · $munLabel (${stringResource(R.string.listings_radius_format, listing.radiusKm)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReputationStars(
                    rating = item.ownerReputationAvg,
                    reviewsCount = item.ownerReviewsCount
                )
            }
        }
    }
}
