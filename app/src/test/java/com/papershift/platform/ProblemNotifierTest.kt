package com.papershift.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import com.papershift.core.ShiftOutcome
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** EC-11 notification content and permission handling. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProblemNotifierTest {
    private lateinit var app: Application
    private lateinit var manager: NotificationManager
    private lateinit var notifier: ProblemNotifier

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        manager = app.getSystemService(NotificationManager::class.java)
        notifier = ProblemNotifier(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun `folder problem posts one alert with a choose folder action`() {
        notifier.show(ShiftOutcome.FOLDER_MISSING)
        val n = posted().single()
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Wallpaper rotation paused")
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Your folder is missing. Tap to choose a new one.")
        assertThat(n.actions.map { it.title.toString() }).containsExactly("Choose folder")
        assertThat(n.channelId).isEqualTo(ProblemNotifier.CHANNEL_ID)
        assertThat(manager.getNotificationChannel(ProblemNotifier.CHANNEL_ID).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun `problems that keep the schedule use the softer title`() {
        notifier.show(ShiftOutcome.NO_IMAGES)
        assertThat(posted().single().extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Wallpaper not changing")
    }

    @Test
    fun `blocked wallpaper has no folder action`() {
        notifier.show(ShiftOutcome.WALLPAPER_NOT_ALLOWED)
        assertThat(posted().single().actions).isNull()
    }

    @Test
    fun `a newer problem replaces the old one and clear removes it`() {
        notifier.show(ShiftOutcome.NO_IMAGES)
        notifier.show(ShiftOutcome.ACCESS_LOST)
        assertThat(posted()).hasSize(1)
        notifier.clear()
        assertThat(posted()).isEmpty()
    }

    @Test
    fun `nothing is posted without permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertThat(notifier.isEnabled()).isFalse()
        notifier.show(ShiftOutcome.FOLDER_MISSING)
        assertThat(posted()).isEmpty()
    }
}
