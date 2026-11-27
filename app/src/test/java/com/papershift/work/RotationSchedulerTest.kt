package com.papershift.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.papershift.core.RotationSettings
import com.papershift.core.Source
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** PRD §4.3, TC-WORK-02, TC-WORK-04, TC-BING-03. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RotationSchedulerTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: RotationScheduler

    private val on = RotationSettings(rotationEnabled = true, folderUri = "content://f")

    /** Bing + unmetered: the test WorkManager keeps work ENQUEUED instead of running the worker. */
    private val waiting = on.copy(source = Source.Bing)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = RotationScheduler(context)
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `gallery needs no network and scheduled runs respect low battery`() {
        val c = RotationScheduler.constraintsFor(on, manual = false)
        assertThat(c.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(c.requiresBatteryNotLow()).isTrue()
    }

    @Test
    fun `bing uses unmetered or connected networks`() {
        val bing = on.copy(source = Source.Bing)
        assertThat(RotationScheduler.constraintsFor(bing, manual = false).requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(RotationScheduler.constraintsFor(bing.copy(unmeteredOnly = false), manual = false).requiredNetworkType)
            .isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `manual shifts ignore the battery pause but keep the network rule`() {
        val c = RotationScheduler.constraintsFor(on.copy(source = Source.Bing), manual = true)
        assertThat(c.requiresBatteryNotLow()).isFalse()
        assertThat(c.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }

    @Test
    fun `start enqueues the immediate shift and the 12 hour schedule`() {
        scheduler.start(waiting)
        val periodic = infos(RotationScheduler.PERIODIC_WORK).single()
        assertThat(periodic.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(12))
        assertThat(periodic.periodicityInfo!!.flexIntervalMillis).isEqualTo(TimeUnit.HOURS.toMillis(2))
        assertThat(infos(RotationScheduler.SHIFT_NOW_WORK).single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `first scheduled rotation is 10 to 12 hours after start`() {
        val before = System.currentTimeMillis()
        scheduler.start(waiting)
        val next = infos(RotationScheduler.PERIODIC_WORK).single().nextScheduleTimeMillis
        assertThat(next - before).isAtLeast(TimeUnit.HOURS.toMillis(10))
        assertThat(next - before).isAtMost(TimeUnit.HOURS.toMillis(12) + TimeUnit.MINUTES.toMillis(1))
    }

    @Test
    fun `double tapping shift now keeps a single request`() {
        scheduler.shiftNow(waiting)
        scheduler.shiftNow(waiting)
        assertThat(infos(RotationScheduler.SHIFT_NOW_WORK)).hasSize(1)
    }

    @Test
    fun `settings change updates constraints in place`() {
        scheduler.updateSchedule(on.copy(source = Source.Bing))
        val before = infos(RotationScheduler.PERIODIC_WORK).single()
        scheduler.updateSchedule(on.copy(source = Source.Bing, unmeteredOnly = false))
        val after = infos(RotationScheduler.PERIODIC_WORK).single()
        assertThat(after.id).isEqualTo(before.id)
        assertThat(after.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun `disabled rotation never enqueues a schedule`() {
        scheduler.updateSchedule(on.copy(rotationEnabled = false))
        assertThat(infos(RotationScheduler.PERIODIC_WORK)).isEmpty()
    }

    @Test
    fun `pause cancels both works`() {
        scheduler.start(waiting)
        scheduler.cancelAll()
        assertThat(infos(RotationScheduler.PERIODIC_WORK).map { it.state }).containsExactly(WorkInfo.State.CANCELLED)
        assertThat(infos(RotationScheduler.SHIFT_NOW_WORK).all { it.state.isFinished }).isTrue()
    }
}
