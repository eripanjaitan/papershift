package com.papershift.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.papershift.MainActivity
import com.papershift.R
import com.papershift.core.NotifierPort
import com.papershift.core.ShiftOutcome

/**
 * The single "rotation problem" notification (PRD EC-11). A newer problem replaces the
 * older one; it is removed when the problem is fixed.
 */
class ProblemNotifier(context: Context) : NotifierPort {
    private val app = context.applicationContext
    private val manager = NotificationManagerCompat.from(app)

    /** True when alerts can actually be shown (permission granted and not blocked by the user). */
    fun isEnabled(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    override fun show(outcome: ShiftOutcome) {
        if (!isEnabled()) return // The in-app banner still explains the problem.
        ensureChannel()
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(if (outcome.stopsRotation) R.string.notif_title_paused else R.string.notif_title_not_changing))
            .setContentText(app.getString(messageFor(outcome)))
            .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(messageFor(outcome))))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(chooseFolder = false))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (outcome.isFolderProblem) {
            builder.addAction(0, app.getString(R.string.notif_action_choose_folder), openApp(chooseFolder = true))
        }
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post; nothing else to do.
        }
    }

    override fun clear() = manager.cancel(NOTIFICATION_ID)

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, app.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = app.getString(R.string.notif_channel_description) }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openApp(chooseFolder: Boolean): PendingIntent {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CHOOSE_FOLDER, chooseFolder)
        return PendingIntent.getActivity(
            app,
            if (chooseFolder) REQUEST_CHOOSE_FOLDER else REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_ID = "rotation_problems"
        const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN = 0
        private const val REQUEST_CHOOSE_FOLDER = 1

        @StringRes
        fun messageFor(outcome: ShiftOutcome): Int = when (outcome) {
            ShiftOutcome.FOLDER_MISSING -> R.string.attention_folder_missing
            ShiftOutcome.ACCESS_LOST -> R.string.attention_access_lost
            ShiftOutcome.NO_IMAGES -> R.string.attention_no_images
            ShiftOutcome.DECODE_FAILED -> R.string.attention_decode_failed
            ShiftOutcome.CHANGED_ELSEWHERE -> R.string.attention_changed_elsewhere
            else -> R.string.attention_not_allowed
        }
    }
}
