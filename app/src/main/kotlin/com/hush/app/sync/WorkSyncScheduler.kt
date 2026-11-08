package com.hush.app.sync

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.hush.app.AppInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** The WorkManager requests both entry points enqueue. Kept together so tests can assert them. */
object SyncRequests {
    const val PERIODIC_NAME = "hush-sync"
    const val ONE_TIME_NAME = "hush-sync-now"
    const val PERIOD_MINUTES = 15L

    val constraints: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodic(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .build()

    fun oneTime(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS, TimeUnit.MILLISECONDS)
            .build()
}

/** "Sync now" from the UI. KEEP: a second tap while one is queued or running is a no-op. */
class WorkSyncScheduler @Inject constructor(@ApplicationContext private val ctx: Context) : SyncScheduler {
    override fun syncNow() {
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(SyncRequests.ONE_TIME_NAME, ExistingWorkPolicy.KEEP, SyncRequests.oneTime())
    }
}

/** Registers the periodic sync at process start. UPDATE keeps the existing schedule when nothing changed. */
class SyncInitializer @Inject constructor() : AppInitializer {
    override fun init(app: Application) {
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork(SyncRequests.PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, SyncRequests.periodic())
    }
}
