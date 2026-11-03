package dev.linkphone.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodic background check; never initializes LinkCore or downloads an APK. */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { UpdateChecker.fetchLatest() }
            .onSuccess { latest ->
                if (UpdateChecker.isNewer(latest.version, BuildConfig.VERSION_NAME)) {
                    UpdateNotifier.show(applicationContext, latest.version)
                } else {
                    UpdateNotifier.clear(applicationContext)
                }
            }
        Result.success()
    }
}
