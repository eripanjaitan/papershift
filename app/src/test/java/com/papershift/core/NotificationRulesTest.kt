package com.papershift.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The notify / skip table from the EC-11 proposal. */
class NotificationRulesTest {

    @Test
    fun `background problems that need the user are shown`() {
        for (outcome in ShiftOutcome.entries.filter { it.needsAttention }) {
            assertThat(NotificationRules.decide(outcome, previous = null, manual = false)).isEqualTo(NotifyAction.Show)
        }
    }

    @Test
    fun `self-healing outcomes are silent`() {
        for (outcome in listOf(ShiftOutcome.FAILED_TRANSIENT, ShiftOutcome.SKIPPED_BATTERY_SAVER)) {
            assertThat(NotificationRules.decide(outcome, previous = ShiftOutcome.NO_IMAGES, manual = false)).isEqualTo(NotifyAction.None)
        }
    }

    @Test
    fun `runs the user started never notify`() {
        assertThat(NotificationRules.decide(ShiftOutcome.FOLDER_MISSING, previous = null, manual = true)).isEqualTo(NotifyAction.None)
    }

    @Test
    fun `the same problem is not announced twice`() {
        assertThat(NotificationRules.decide(ShiftOutcome.NO_IMAGES, previous = ShiftOutcome.NO_IMAGES, manual = false))
            .isEqualTo(NotifyAction.None)
    }

    @Test
    fun `a different problem replaces the old one`() {
        assertThat(NotificationRules.decide(ShiftOutcome.ACCESS_LOST, previous = ShiftOutcome.NO_IMAGES, manual = false))
            .isEqualTo(NotifyAction.Show)
    }

    @Test
    fun `success clears any alert, manual or not`() {
        assertThat(NotificationRules.decide(ShiftOutcome.SUCCESS, previous = ShiftOutcome.NO_IMAGES, manual = false)).isEqualTo(NotifyAction.Clear)
        assertThat(NotificationRules.decide(ShiftOutcome.SUCCESS, previous = null, manual = true)).isEqualTo(NotifyAction.Clear)
    }
}
