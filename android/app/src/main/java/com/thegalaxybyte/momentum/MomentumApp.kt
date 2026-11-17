package com.thegalaxybyte.momentum

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thegalaxybyte.momentum.data.Catalogs
import com.thegalaxybyte.momentum.data.PlanService
import com.thegalaxybyte.momentum.data.Prefs
import com.thegalaxybyte.momentum.data.Repo
import com.thegalaxybyte.momentum.data.TokenStore
import com.thegalaxybyte.momentum.data.coach.CoachClient
import com.thegalaxybyte.momentum.data.coach.CoachService
import com.thegalaxybyte.momentum.data.db.MomentumDb
import com.thegalaxybyte.momentum.data.health.HealthSource
import com.thegalaxybyte.momentum.data.sync.SyncEngine
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val db = MomentumDb.create(context)
    val dao = db.dao()
    val catalogs = Catalogs(context)
    val prefs = Prefs(context)
    val tokens = TokenStore(context)
    val health = HealthSource(context, dao)
    val sync = SyncEngine(dao, prefs, tokens)
    val repo = Repo(dao, catalogs, health) { SyncWorker.scheduleSoon(context) }
    val plan = PlanService(dao, catalogs, repo)
    val coach = CoachService(CoachClient(prefs, tokens), repo, plan)
}

class MomentumApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedulePeriodic(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as MomentumApp).container

/** Imports recent Health Connect data, then syncs with the backend. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        runCatching { c.health.importRecent() }
        runCatching { c.plan.ensureWeeklyUpdates() }
        return if (c.sync.sync().isSuccess || runAttemptCount >= 3) Result.success() else Result.retry()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun scheduleSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-soon", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(5, TimeUnit.SECONDS).setConstraints(online).build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build(),
            )
        }
    }
}
