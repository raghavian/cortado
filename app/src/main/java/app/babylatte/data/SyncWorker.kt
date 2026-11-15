package app.babylatte.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.babylatte.BabylatteApp
import java.util.concurrent.TimeUnit

/** Refreshes the offline café data once a day in the background, whenever there's a network. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = (applicationContext as BabylatteApp).offline.sync()
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("offline-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
