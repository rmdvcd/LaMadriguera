package com.lamadriguera.app.feature.listings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.BidVisibility
import com.lamadriguera.app.domain.model.CubanMunicipalities
import com.lamadriguera.app.domain.model.ListingKind
import com.lamadriguera.app.domain.model.ListingVisibility
import com.lamadriguera.app.domain.model.PrivacyLevel
import com.lamadriguera.app.feature.auction.AuctionConfigScreen
import com.lamadriguera.app.ui.components.LoadingState
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Formulario de creación y edición de ofertas (`kind = 'offer'`) y demandas
 * (`kind = 'request'`), con compresión de foto en cliente (`<= 1 MB`) y
 * switch «¿Permitir subasta?» que muestra [AuctionConfigScreen].
 */
@Composable
fun CreateListingScreen(
    state: CreateListingUiState,
    onKindChanged: (ListingKind) -> Unit,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onCategorySelected: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    onPriceNegotiableChanged: (Boolean) -> Unit,
    onMunicipalitySelected: (Int?) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onPrivacyLevelChanged: (PrivacyLevel) -> Unit,
    onVisibilityChanged: (ListingVisibility) -> Unit,
    onPhotoSelected: (ByteArray) -> Unit,
    onPhotoRemoved: () -> Unit,
    onAllowAuctionChanged: (Boolean) -> Unit,
    onOpenAuctionConfig: () -> Unit,
    onCloseAuctionConfig: () -> Unit,
    onAuctionStartingPriceChanged: (String) -> Unit,
    onAuctionMinIncrementChanged: (String) -> Unit,
    onAuctionReservePriceChanged: (String) -> Unit,
    onAuctionStartsAtChanged: (String) -> Unit,
    onAuctionEndsAtChanged: (String) -> Unit,
    onAuctionAutoExtendChanged: (String) -> Unit,
    onAuctionRequiresIdentityChanged: (Boolean) -> Unit,
    onAuctionVisibleBidsChanged: (BidVisibility) -> Unit,
    onConfirmAuctionConfig: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        LoadingState(modifier = modifier.fillMaxSize())
        return
    }

    if (state.showAuctionConfigScreen) {
        AuctionConfigScreen(
            state = state.auctionConfigState,
            onStartingPriceChanged = onAuctionStartingPriceChanged,
            onMinIncrementChanged = onAuctionMinIncrementChanged,
            onReservePriceChanged = onAuctionReservePriceChanged,
            onStartsAtChanged = onAuctionStartsAtChanged,
            onEndsAtChanged = onAuctionEndsAtChanged,
            onAutoExtendMinutesChanged = onAuctionAutoExtendChanged,
            onRequiresIdentityChanged = onAuctionRequiresIdentityChanged,
            onVisibleBidsChanged = onAuctionVisibleBidsChanged,
            onSave = onConfirmAuctionConfig,
            onCancel = onCloseAuctionConfig,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    onPhotoSelected(bytes)
                }
            }
        }
    }

    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var municipalityMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = if (state.editingListingId == null) {
                stringResource(R.string.listings_create)
            } else {
                stringResource(R.string.listings_edit)
            },
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Tipo: Oferta / Demanda
        Text(
            text = stringResource(R.string.listing_form_kind_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = state.kind == ListingKind.OFFER,
                onClick = { onKindChanged(ListingKind.OFFER) },
                enabled = !state.isSubmitting,
                label = { Text(stringResource(R.string.listings_kind_offer)) }
            )
            FilterChip(
                selected = state.kind == ListingKind.REQUEST,
                onClick = { onKindChanged(ListingKind.REQUEST) },
                enabled = !state.isSubmitting,
                label = { Text(stringResource(R.string.listings_kind_request)) }
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.titleInput,
            onValueChange = onTitleChanged,
            label = { Text(stringResource(R.string.listing_form_title_label)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = state.descriptionInput,
            onValueChange = onDescriptionChanged,
            label = { Text(stringResource(R.string.listing_form_description_label)) },
            minLines = 3,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Categoría
        Text(
            text = stringResource(R.string.listing_form_category_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { categoryMenuExpanded = true },
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                val categoryName = state.categories
                    .firstOrNull { it.id == state.selectedCategoryId }?.name
                    ?: "General"
                Text(text = categoryName)
            }
            DropdownMenu(
                expanded = categoryMenuExpanded,
                onDismissRequest = { categoryMenuExpanded = false }
            ) {
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
        Spacer(modifier = Modifier.height(12.dp))

        // Precio o "A convenir"
        if (!state.allowAuction) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.listing_form_price_negotiable_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = state.priceNegotiable,
                    onCheckedChange = onPriceNegotiableChanged,
                    enabled = !state.isSubmitting
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.priceInput,
                onValueChange = onPriceChanged,
                label = { Text(stringResource(R.string.listing_form_price_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !state.isSubmitting && !state.priceNegotiable,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Municipio
        Text(
            text = stringResource(R.string.listing_form_municipality_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { municipalityMenuExpanded = true },
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = CubanMunicipalities.displayName(state.selectedMunicipalityId))
            }
            DropdownMenu(
                expanded = municipalityMenuExpanded,
                onDismissRequest = { municipalityMenuExpanded = false }
            ) {
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
        Spacer(modifier = Modifier.height(12.dp))

        // Radio en km
        Text(
            text = stringResource(R.string.listing_form_radius_label, state.radiusKm),
            style = MaterialTheme.typography.titleSmall
        )
        Slider(
            value = state.radiusKm.toFloat(),
            onValueChange = { onRadiusChanged(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 19,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Foto opcional (máx 1 MB, compresión en cliente con Coil / Compressor)
        Text(
            text = stringResource(R.string.listing_form_photo_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = { photoLauncher.launch("image/*") },
                enabled = !state.isSubmitting
            ) {
                Text(stringResource(R.string.listing_form_photo_select))
            }
            if (state.compressedPhotoBytes != null || state.existingPhotoUrl != null) {
                TextButton(
                    onClick = onPhotoRemoved,
                    enabled = !state.isSubmitting
                ) {
                    Text(stringResource(R.string.listing_form_photo_remove))
                }
            }
        }
        val previewModel: Any? = state.compressedPhotoBytes ?: state.existingPhotoUrl
        if (previewModel != null) {
            Spacer(modifier = Modifier.height(8.dp))
            AsyncImage(
                model = previewModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
            state.photoSizeKb?.let { kb ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.listing_form_photo_compressed_info, kb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Privacidad public / anon
        Text(
            text = stringResource(R.string.listing_form_privacy_label),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        RadioRow(
            title = stringResource(R.string.privacy_level_public),
            selected = state.privacyLevel == PrivacyLevel.PUBLIC,
            enabled = !state.isSubmitting,
            onClick = { onPrivacyLevelChanged(PrivacyLevel.PUBLIC) }
        )
        RadioRow(
            title = stringResource(R.string.privacy_level_anon),
            selected = state.privacyLevel == PrivacyLevel.ANON,
            enabled = !state.isSubmitting,
            onClick = { onPrivacyLevelChanged(PrivacyLevel.ANON) }
        )

        // Visibilidad configurable si es demanda (kind = 'request'): pública o solo proveedores
        if (state.kind == ListingKind.REQUEST) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.listing_form_visibility_label),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            RadioRow(
                title = stringResource(R.string.listing_form_visibility_public),
                selected = state.visibility == ListingVisibility.PUBLIC,
                enabled = !state.isSubmitting,
                onClick = { onVisibilityChanged(ListingVisibility.PUBLIC) }
            )
            RadioRow(
                title = stringResource(R.string.listing_form_visibility_providers),
                selected = state.visibility == ListingVisibility.PROVIDERS_ONLY,
                enabled = !state.isSubmitting,
                onClick = { onVisibilityChanged(ListingVisibility.PROVIDERS_ONLY) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Switch "¿Permitir subasta?" -> muestra AuctionConfigScreen
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.listing_form_allow_auction_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.listing_form_allow_auction_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.allowAuction,
                        onCheckedChange = onAllowAuctionChanged,
                        enabled = !state.isSubmitting && state.canEditAuction
                    )
                }

                if (state.allowAuction && state.auctionConfig != null) {
                    val cfg = state.auctionConfig
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Inicial: ${cfg.startingPrice.stripTrailingZeros().toPlainString()} CUP · " +
                            "Incremento: +${cfg.minIncrement.stripTrailingZeros().toPlainString()} CUP",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    cfg.reservePrice?.let { reserve ->
                        Text(
                            text = "Reserva: ${reserve.stripTrailingZeros().toPlainString()} CUP",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "Auto-extensión: ${cfg.autoExtendMinutes} min · Pujas visibles: ${cfg.visibleBids.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenAuctionConfig,
                        enabled = !state.isSubmitting && state.canEditAuction
                    ) {
                        Text(stringResource(R.string.listing_form_edit_auction_config))
                    }
                }
            }
        }

        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onSubmit,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                val buttonText = when {
                    state.editingListingId != null -> stringResource(R.string.listing_form_save_changes)
                    state.kind == ListingKind.OFFER -> stringResource(R.string.listing_form_submit_offer)
                    else -> stringResource(R.string.listing_form_submit_request)
                }
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun RadioRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
