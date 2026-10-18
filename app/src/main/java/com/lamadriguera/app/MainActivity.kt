package com.lamadriguera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lamadriguera.app.ui.navigation.LaMadrigueraNavGraph
import com.lamadriguera.app.ui.theme.LaMadrigueraTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Punto de entrada de la app.
 * Sin lógica de negocio: solo Compose + NavGraph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash API - mantener hasta que el tema esté listo
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaMadrigueraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LaMadrigueraNavGraph(initialDeepLink = notificationDeepLink())
                }
            }
        }
    }

    /** `lamadriguera://notifications` cuando la app se abre desde un aviso. */
    private fun notificationDeepLink(): String? =
        intent?.takeIf { it.action == android.content.Intent.ACTION_VIEW }?.dataString
}
