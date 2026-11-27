package com.papershift.core

/** What the Main screen shows (PRD §2.3 status table). */
sealed interface RotationStatus {
    data object Off : RotationStatus
    data object Running : RotationStatus
    data class Scheduled(val nextAt: Long?) : RotationStatus
    data class Skipped(val nextAt: Long?) : RotationStatus
    data class Waiting(val reason: WaitReason) : RotationStatus
    data class NeedsAttention(val outcome: ShiftOutcome) : RotationStatus
}

enum class WaitReason { Network, UnmeteredNetwork, Battery }

enum class WorkPhase { Enqueued, Running, Finished }

/** The parts of a WorkManager WorkInfo the status needs. */
data class WorkSnapshot(val phase: WorkPhase, val nextScheduleAt: Long?)

/** Current device conditions, read by the UI to explain why a due run hasn't happened. */
data class DeviceConditions(val connected: Boolean, val unmetered: Boolean, val batteryLow: Boolean)

object StatusRules {
    fun derive(
        settings: RotationSettings,
        state: RotationState,
        periodic: WorkSnapshot?,
        shiftNow: WorkSnapshot?,
        conditions: DeviceConditions,
        now: Long,
    ): RotationStatus {
        val outcome = state.lastOutcome
        if (!settings.rotationEnabled) {
            return if (outcome != null && outcome.stopsRotation) RotationStatus.NeedsAttention(outcome) else RotationStatus.Off
        }
        if (periodic?.phase == WorkPhase.Running || shiftNow?.phase == WorkPhase.Running) return RotationStatus.Running
        if (outcome != null && outcome.needsAttention) return RotationStatus.NeedsAttention(outcome)

        val manualPending = shiftNow?.phase == WorkPhase.Enqueued
        val nextAt = periodic?.nextScheduleAt?.takeIf { periodic.phase == WorkPhase.Enqueued }
        val periodicDue = nextAt != null && nextAt <= now
        if (manualPending || periodicDue) {
            networkWait(settings, conditions)?.let { return RotationStatus.Waiting(it) }
            // Manual shifts ignore the battery pause (PRD §4.4).
            if (periodicDue && !manualPending && settings.pauseOnLowBattery && conditions.batteryLow) {
                return RotationStatus.Waiting(WaitReason.Battery)
            }
        }
        if (outcome == ShiftOutcome.SKIPPED_BATTERY_SAVER) return RotationStatus.Skipped(nextAt)
        return RotationStatus.Scheduled(nextAt)
    }

    private fun networkWait(settings: RotationSettings, conditions: DeviceConditions): WaitReason? = when {
        settings.source != Source.Bing -> null
        !conditions.connected -> WaitReason.Network
        settings.unmeteredOnly && !conditions.unmetered -> WaitReason.UnmeteredNetwork
        else -> null
    }

    /** "5h 20m", "20m", or null when less than a minute is left. */
    fun formatRemaining(millis: Long): String? {
        val minutes = millis / 60_000
        if (minutes < 1) return null
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
