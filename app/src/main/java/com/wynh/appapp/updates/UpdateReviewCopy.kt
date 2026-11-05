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

    /** Compares dotted numeric versions ("1.2.10" > "1.2.9"); null when either is not of that form. */
    fun compareVersions(a: String, b: String): Int? {
        val left = a.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
        val right = b.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    /** The review's version line: newer, older, the same number with changed files, or unknown. */
    fun versionLine(current: String?, incoming: String): String {
        if (current == null) return "Version $incoming"
        return when (compareVersions(incoming, current)) {
            null -> if (current == incoming) "Version $incoming, with file changes" else "Version $current → $incoming"
            0 -> "Version $incoming, with file changes"
            in 1..Int.MAX_VALUE -> "New version: $current → $incoming"
            else -> "⚠ Older version: $current → $incoming"
        }
    }
}
