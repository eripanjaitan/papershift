package com.papershift.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** PRD §2.3 status table. */
class StatusRulesTest {
    private val now = 1_000_000L
    private val online = DeviceConditions(connected = true, unmetered = true, batteryLow = false)
    private val on = RotationSettings(rotationEnabled = true, folderUri = "content://f")
    private val scheduled = WorkSnapshot(WorkPhase.Enqueued, nextScheduleAt = now + 3_600_000)
    private val overdue = WorkSnapshot(WorkPhase.Enqueued, nextScheduleAt = now - 1)

    private fun derive(
        settings: RotationSettings = on,
        state: RotationState = RotationState(),
        periodic: WorkSnapshot? = scheduled,
        shiftNow: WorkSnapshot? = null,
        conditions: DeviceConditions = online,
    ) = StatusRules.derive(settings, state, periodic, shiftNow, conditions, now)

    @Test
    fun `off when rotation is disabled`() {
        assertThat(derive(settings = on.copy(rotationEnabled = false), periodic = null)).isEqualTo(RotationStatus.Off)
    }

    @Test
    fun `needs attention when an error stopped rotation`() {
        val status = derive(
            settings = on.copy(rotationEnabled = false),
            state = RotationState(lastOutcome = ShiftOutcome.FOLDER_MISSING),
            periodic = null,
        )
        assertThat(status).isEqualTo(RotationStatus.NeedsAttention(ShiftOutcome.FOLDER_MISSING))
    }

    @Test
    fun `needs attention but still scheduled for an empty folder`() {
        val status = derive(state = RotationState(lastOutcome = ShiftOutcome.NO_IMAGES))
        assertThat(status).isEqualTo(RotationStatus.NeedsAttention(ShiftOutcome.NO_IMAGES))
    }

    @Test
    fun `running wins over other states`() {
        assertThat(derive(shiftNow = WorkSnapshot(WorkPhase.Running, null))).isEqualTo(RotationStatus.Running)
    }

    @Test
    fun `scheduled shows the next run time`() {
        assertThat(derive()).isEqualTo(RotationStatus.Scheduled(now + 3_600_000))
    }

    @Test
    fun `bing waits for unmetered network when a run is due`() {
        val status = derive(
            settings = on.copy(source = Source.Bing),
            periodic = overdue,
            conditions = online.copy(unmetered = false),
        )
        assertThat(status).isEqualTo(RotationStatus.Waiting(WaitReason.UnmeteredNetwork))
    }

    @Test
    fun `bing does not wait when no run is due`() {
        val status = derive(settings = on.copy(source = Source.Bing), conditions = online.copy(connected = false, unmetered = false))
        assertThat(status).isEqualTo(RotationStatus.Scheduled(now + 3_600_000))
    }

    @Test
    fun `gallery never waits for network`() {
        val status = derive(periodic = overdue, conditions = online.copy(connected = false, unmetered = false))
        assertThat(status).isEqualTo(RotationStatus.Scheduled(now - 1))
    }

    @Test
    fun `due periodic run waits for battery but a manual shift does not`() {
        val lowBattery = online.copy(batteryLow = true)
        assertThat(derive(periodic = overdue, conditions = lowBattery)).isEqualTo(RotationStatus.Waiting(WaitReason.Battery))
        assertThat(derive(periodic = overdue, shiftNow = WorkSnapshot(WorkPhase.Enqueued, null), conditions = lowBattery))
            .isEqualTo(RotationStatus.Scheduled(now - 1))
    }

    @Test
    fun `skipped after battery saver`() {
        val status = derive(state = RotationState(lastOutcome = ShiftOutcome.SKIPPED_BATTERY_SAVER))
        assertThat(status).isEqualTo(RotationStatus.Skipped(now + 3_600_000))
    }

    @Test
    fun `remaining time formatting`() {
        assertThat(StatusRules.formatRemaining((5 * 60 + 20) * 60_000L)).isEqualTo("5h 20m")
        assertThat(StatusRules.formatRemaining(20 * 60_000L)).isEqualTo("20m")
        assertThat(StatusRules.formatRemaining(30_000L)).isNull()
        assertThat(StatusRules.formatRemaining(-5_000L)).isNull()
    }
}
