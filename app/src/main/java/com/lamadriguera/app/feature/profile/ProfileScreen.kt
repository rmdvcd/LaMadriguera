package com.lamadriguera.app.feature.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lamadriguera.app.R
import com.lamadriguera.app.domain.model.AccountKind
import com.lamadriguera.app.domain.model.AuthSession
import com.lamadriguera.app.domain.model.VerificationLevel
import com.lamadriguera.app.ui.components.ReputationStars

/**
 * Perfil: avatar, nombre, alias, municipio, reputación (estrellas + reseñas) y
 * bio. Además expone el estado de la cuenta (temp/verificada) y las entradas a
 * verificación de identidad, privacidad y edición.
 */
@Composable
fun ProfileScreen(
    session: AuthSession,
    onEditProfile: () -> Unit,
    onOpenPrivacySettings: () -> Unit,
    onOpenIdentityVerification: () -> Unit,
    onOpenPayments: () -> Unit,
    onLogout: () -> Unit
) {
    val profile = session.profile
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (profile?.avatarUrl != null) {
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = profile?.displayName.orEmpty(),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = profile?.alias?.let { "@$it" }
                ?: stringResource(R.string.profile_alias_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = profile?.municipalityId
                ?.let { stringResource(R.string.profile_municipio_value, it) }
                ?: stringResource(R.string.profile_municipio_none),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        ReputationStars(
            rating = profile?.reputationAvg ?: java.math.BigDecimal.ZERO,
            reviewsCount = profile?.reviewsCount ?: 0
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.profile_bio),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = profile?.bio?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.profile_bio_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        AccountStatusCard(
            session = session,
            onOpenIdentityVerification = onOpenIdentityVerification
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onEditProfile,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.profile_edit))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenPrivacySettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.profile_privacy_settings))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenPayments,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.nav_payments))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.auth_logout))
        }
    }
}

@Composable
private fun AccountStatusCard(
    session: AuthSession,
    onOpenIdentityVerification: () -> Unit
) {
    val capabilities = session.capabilities
    val statusText = when {
        capabilities.isTemp -> stringResource(R.string.profile_status_temp)
        session.user.verificationLevel == VerificationLevel.IDENTITY ->
            stringResource(R.string.profile_status_identity)
        else -> stringResource(R.string.profile_status_phone)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (capabilities.isTemp) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.profile_account_status),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (capabilities.isTemp) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (session.accountKind == AccountKind.SECONDARY) {
                    stringResource(R.string.profile_account_kind_secondary)
                } else {
                    stringResource(R.string.profile_account_kind_primary)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (capabilities.isTemp ||
                session.user.verificationLevel != VerificationLevel.IDENTITY
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.Start) {
                    Button(onClick = onOpenIdentityVerification) {
                        Text(stringResource(R.string.profile_verify_identity))
                    }
                }
            }
        }
    }
}
