package com.wynh.appapp.updates

import com.wynh.appapp.grants.SubappCapability

/** Wording of the update review about permissions; pure so it can be tested without Android. */
object UpdateReviewCopy {
    private fun label(capability: SubappCapability) = when (capability) {
        SubappCapability.AI -> "AI"
        SubappCapability.NOTIFICATIONS -> "Notifications"
        SubappCapability.NETWORK -> "Internet"
    }

    /** The user's current decisions, which an update never changes. */
    fun permissionSummary(granted: Set<SubappCapability>): String {
        val current = SubappCapability.entries.filter { it in granted }
        val kept = if (current.isEmpty()) "Existing permissions are kept. Current permissions: none."
            else "Existing permissions are kept. Current permissions: ${current.joinToString(", ", transform = ::label)}."
        return "$kept Permissions that are off stay off."
    }

    /** Shown first in the review when the package declares a different publisher ID or GitHub source. */
    fun identityWarning(mismatch: String, granted: Set<SubappCapability>): String =
        "⚠ The publisher identity or source differs from the installed app:\n$mismatch\n\n" +
            permissionSummary(granted) + "\nOnly continue if you trust this repository."
}
