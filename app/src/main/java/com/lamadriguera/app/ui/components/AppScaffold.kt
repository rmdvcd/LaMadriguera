package com.lamadriguera.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable

/**
 * Scaffold base de la app. Centraliza padding, snackbar host y theming.
 * Las pantallas reales lo usarán; por ahora es referencia para futuros features.
 */
@Composable
fun AppScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = topBar,
        bottomBar = bottomBar,
        content = content
    )
}
