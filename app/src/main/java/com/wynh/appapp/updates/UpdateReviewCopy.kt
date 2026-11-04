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

    /** Plain-language reason a check failed, followed by the source it tried. */
    fun checkFailure(e: Throwable, source: GitHubSource): String {
        val message = e.message.orEmpty()
        val repo = "${source.owner}/${source.repository}"
        val reason = when {
            "(422)" in message || "No commit found" in message -> "Branch \"${source.branch}\" wasn't found in $repo."
            "(404)" in message -> "$repo wasn't found. Check the name; private repositories aren't supported."
            "(403)" in message || "(429)" in message -> "GitHub is limiting requests right now. Try again in a few minutes."
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
                "Couldn't reach GitHub. Check your internet connection."
            message.isNotBlank() -> message
            else -> "Something went wrong while checking GitHub."
        }
        return "$reason\n\n${source.display}"
    }
}
