package com.papershift.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.papershift.core.RotationSettings
import com.papershift.core.Source
import com.papershift.core.WorkPhase
import com.papershift.core.WorkSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** WorkManager setup from PRD §4.3. */
class RotationScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    /** Start: immediate shift plus the 12-hour schedule. */
    fun start(settings: RotationSettings) {
        shiftNow(settings)
        updateSchedule(settings)
    }

    /**
     * Enqueues (or updates) the periodic work. UPDATE keeps the existing cadence, so a
     * settings change applies new constraints without moving the next run (TC-WORK-04).
     *
     * No initial delay: WorkManager already runs the first period inside its flex window,
     * 10–12 h after enqueue. Adding a 12 h delay on top pushed the first run to ~22 h.
     */
    fun updateSchedule(settings: RotationSettings) {
        if (!settings.rotationEnabled) return
        val request = PeriodicWorkRequestBuilder<ShiftWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS,
            FLEX_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(constraintsFor(settings, manual = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(ShiftWorker.KEY_MANUAL to false))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Start and Shift Now. KEEP ignores double taps (TC-WORK-06). */
    fun shiftNow(settings: RotationSettings) {
        val request = OneTimeWorkRequestBuilder<ShiftWorker>()
            .setConstraints(constraintsFor(settings, manual = true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(ShiftWorker.KEY_MANUAL to true))
            .build()
        workManager.enqueueUniqueWork(SHIFT_NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(SHIFT_NOW_WORK)
    }

    fun periodicWork(): Flow<WorkSnapshot?> = snapshots(PERIODIC_WORK)

    fun shiftNowWork(): Flow<WorkSnapshot?> = snapshots(SHIFT_NOW_WORK)

    private fun snapshots(name: String): Flow<WorkSnapshot?> =
        workManager.getWorkInfosForUniqueWorkFlow(name).map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished } ?: return@map null
            WorkSnapshot(
                phase = if (info.state == WorkInfo.State.RUNNING) WorkPhase.Running else WorkPhase.Enqueued,
                nextScheduleAt = info.nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE },
            )
        }

    companion object {
        const val PERIODIC_WORK = "papershift.rotation"
        const val SHIFT_NOW_WORK = "papershift.shift_now"
        const val INTERVAL_HOURS = 12L
        const val FLEX_HOURS = 2L
        const val BACKOFF_MINUTES = 15L

        /**
         * Network only matters for Bing. The battery pause applies to scheduled runs only;
         * Start and Shift Now are user-initiated and ignore it (PRD §4.4).
         */
        fun constraintsFor(settings: RotationSettings, manual: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when {
                    settings.source != Source.Bing -> NetworkType.NOT_REQUIRED
                    settings.unmeteredOnly -> NetworkType.UNMETERED
                    else -> NetworkType.CONNECTED
                },
            )
            .setRequiresBatteryNotLow(!manual && settings.pauseOnLowBattery)
            .build()
    }
}
