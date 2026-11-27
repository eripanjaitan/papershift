package com.papershift.core

/** Posts or removes the single "rotation problem" notification (PRD EC-11). */
interface NotifierPort {
    fun show(outcome: ShiftOutcome)
    fun clear()
}

enum class NotifyAction { Show, Clear, None }

object NotificationRules {
    /**
     * Notify only when the user has to act, only for background runs (the in-app banner
     * covers runs the user started), and only when the outcome changed, so an empty folder
     * alerts once rather than every 12 hours. A successful shift clears any old alert.
     */
    fun decide(outcome: ShiftOutcome, previous: ShiftOutcome?, manual: Boolean): NotifyAction = when {
        outcome == ShiftOutcome.SUCCESS -> NotifyAction.Clear
        !outcome.needsAttention -> NotifyAction.None
        manual -> NotifyAction.None
        outcome == previous -> NotifyAction.None
        else -> NotifyAction.Show
    }
}
