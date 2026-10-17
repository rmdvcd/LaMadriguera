package com.lamadriguera.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lamadriguera.app.feature.auction.worker.AuctionClosureWorker
import dagger.hilt.android.HiltAndroidApp
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

    override fun onCreate() {
        super.onCreate()
        runCatching { AuctionClosureWorker.schedulePeriodic(this) }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
