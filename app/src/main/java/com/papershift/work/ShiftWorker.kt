package com.papershift.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.papershift.PaperShiftApp

/**
 * Thin WorkManager adapter around ShiftEngine. Constraints were already checked by
 * WorkManager before this runs (PRD §2.2).
 */
class ShiftWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as PaperShiftApp).graph
        val result = graph.engine.run(
            manual = inputData.getBoolean(KEY_MANUAL, false),
            attempt = runAttemptCount,
        )
        if (result.stopsRotation) {
            // The engine has recorded the outcome and turned rotation off; drop the schedule.
            // This may cancel the work that is running now, which is fine at this point.
            graph.scheduler.cancelAll()
        }
        // Periodic work stays scheduled after success; FAILED_TRANSIENT also returns success
        // so the 12-hour cadence is unaffected (PRD §2.2 notes).
        return if (result.retry) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}
