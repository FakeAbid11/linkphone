package dev.linkphone.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the background update check; safe to call once per process. */
object UpdateScheduler {
    private const val PERIODIC_WORK = "linkphone-update-check"
    private const val IMMEDIATE_WORK = "linkphone-update-check-now"
    private const val INTERVAL_HOURS = 6L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val manager = WorkManager.getInstance(context)

        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS
        ).setConstraints(constraints).build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        val immediate = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        manager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, immediate)
    }
}
