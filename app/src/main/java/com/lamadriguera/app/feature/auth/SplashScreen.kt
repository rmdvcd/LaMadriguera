package com.lamadriguera.app.feature.auth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.lamadriguera.app.core.result.UiState
import com.lamadriguera.app.domain.model.AuthSession
import com.lamadriguera.app.ui.components.ErrorState
import com.lamadriguera.app.ui.components.LoadingState

/**
 * Arranque: resuelve la sesión guardada y decide entre [onAuthenticated]
 * (Publicaciones) y [onUnauthenticated] (PhoneLogin).
 */
@Composable
fun SplashScreen(
    state: UiState<AuthSession?>,
    onRetry: () -> Unit,
    onAuthenticated: () -> Unit,
    onUnauthenticated: () -> Unit
) {
    when (state) {
        is UiState.Loading, UiState.Idle -> LoadingState(modifier = Modifier.fillMaxSize())
        is UiState.Success -> {
            val target = if (state.data != null) onAuthenticated else onUnauthenticated
            LaunchedEffect(state.data) { target() }
        }
        is UiState.Error -> ErrorState(
            message = state.message,
            onRetry = onRetry,
            modifier = Modifier.fillMaxSize()
        )
    }
}
