package com.lamadriguera.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R
import java.math.BigDecimal

/**
 * Reputación: estrellas (0–5, con media estrella) + número de reseñas.
 * El valor viene de profiles.reputation_avg (calculado por trigger en reviews).
 */
@Composable
fun ReputationStars(
    rating: BigDecimal,
    reviewsCount: Int,
    modifier: Modifier = Modifier
) {
    val value = rating.toDouble().coerceIn(0.0, 5.0)
    val label = stringResource(R.string.profile_reviews_count, reviewsCount)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (index in 0 until 5) {
            val icon = when {
                value >= index + 1.0 -> Icons.Filled.Star
                value >= index + 0.5 -> Icons.Filled.StarHalf
                else -> Icons.Filled.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
