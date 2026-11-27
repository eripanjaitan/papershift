package com.papershift.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.papershift.work.ShiftWorker

/**
 * Debug builds only. Runs a shift exactly like a scheduled background run (manual = false),
 * without waiting 10–12 h; WorkManager ignores `cmd jobscheduler run -f` for periodic work
 * that isn't due yet. Restricted to adb by the DUMP permission in the debug manifest:
 *
 *   adb shell am broadcast -n com.papershift/.debug.DebugShiftReceiver
 */
class DebugShiftReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = OneTimeWorkRequestBuilder<ShiftWorker>()
            .setInputData(workDataOf(ShiftWorker.KEY_MANUAL to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("papershift.debug_shift", ExistingWorkPolicy.REPLACE, request)
    }
}
