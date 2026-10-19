package com.wynh.appapp.ai

internal data class AiNotificationEntry(val requestId: String, val notificationId: Int)

/**
 * [foreground] is the entry that carries the service's foreground state, [others] are ordinary
 * ongoing notifications, and [cancel] lists notifications that no longer have an unfinished request.
 * Apply in that order: promote the foreground entry before cancelling the previous one, because the
 * system ignores cancelling the current foreground notification.
 */
internal data class AiNotificationPlan(
    val foreground: AiNotificationEntry?,
    val others: List<AiNotificationEntry>,
    val cancel: List<Int>
)

/**
 * Pure decision logic for the foreground service notifications. Every call is computed from a fresh
 * snapshot of unfinished requests, so a start, finish or cancel arriving in any order converges on
 * the same result. Call it from one thread (the main thread).
 */
internal class AiNotificationReconciler {
    private var primary: Int? = null
    private var posted: Set<Int> = emptySet()

    fun plan(active: List<AiNotificationEntry>): AiNotificationPlan {
        val ordered = active.distinctBy { it.notificationId }.sortedBy { it.notificationId }
        val foreground = ordered.firstOrNull { it.notificationId == primary } ?: ordered.firstOrNull()
        val ids = ordered.map { it.notificationId }.toSet()
        val plan = AiNotificationPlan(foreground, ordered.filter { it != foreground }, (posted - ids).sorted())
        primary = foreground?.notificationId
        posted = ids
        return plan
    }

    fun reset() { primary = null; posted = emptySet() }
}
