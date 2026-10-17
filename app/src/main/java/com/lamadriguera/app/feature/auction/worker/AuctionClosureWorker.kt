package com.lamadriguera.app.feature.auction.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lamadriguera.app.core.result.Result
import com.lamadriguera.app.domain.repository.AuctionRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Job programado (WorkManager) que invoca periódicamente la función RPC
 * `close_due_auctions()` en Supabase para promover subastas programadas a
 * `auction_live` y cerrar subastas vencidas resolviendo su estado terminal:
 * `awarded`, `no_reserve_met` o `expired`.
 */
class AuctionClosureWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun auctionRepository(): AuctionRepository
    }

    override suspend fun doWork(): ListenableResult {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java
        )
        return when (entryPoint.auctionRepository().closeDueAuctions()) {
            is Result.Success -> ListenableResult.success()
            is Result.Error -> if (runAttemptCount < MAX_RETRIES) {
                ListenableResult.retry()
            } else {
                ListenableResult.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "lamadriguera_auction_closure_periodic"
        private const val MAX_RETRIES = 3

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AuctionClosureWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun triggerImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<AuctionClosureWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

private typealias ListenableResult = androidx.work.ListenableWorker.Result
