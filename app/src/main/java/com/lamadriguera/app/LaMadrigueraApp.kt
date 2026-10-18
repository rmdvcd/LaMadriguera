package com.lamadriguera.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lamadriguera.app.core.push.PushSupport
import com.lamadriguera.app.feature.auction.worker.AuctionClosureWorker
import com.lamadriguera.app.feature.notifications.PushRegistrationManager
import dagger.hilt.android.HiltAndroidApp
import dagger.Lazy
import javax.inject.Inject

/**
 * Application class de La Madriguera.
 * Hilt + WorkManager con HiltWorkerFactory para reintentos de sincronización
 * y cierre periódico de subastas vencidas.
 */
@HiltAndroidApp
class LaMadrigueraApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Lazy: no construye la capa de red/Supabase durante el arranque del
    // proceso; el observador de sesión levanta el registro push cuando toca.
    @Inject
    lateinit var pushRegistration: Lazy<PushRegistrationManager>

    override fun onCreate() {
        super.onCreate()
        runCatching { AuctionClosureWorker.schedulePeriodic(this) }
        // Push opcional (fuera de Play): canales + Firebase solo si la build
        // trae FCM_PROJECT_ID/GOOGLE_API_KEY; observe sesión → registra token.
        runCatching { PushSupport.ensureInitialized(this) }
        runCatching { pushRegistration.get().start() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
