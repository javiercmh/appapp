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
        val kept = if (current.isEmpty()) "Existing permissions are kept: none."
            else "Existing permissions are kept: ${current.joinToString(", ", transform = ::label)}."
        return kept
    }

    /** Shown first in the review when the package declares a different publisher ID or GitHub source. */
    fun identityWarning(mismatch: String, granted: Set<SubappCapability>): String =
        "⚠ This update doesn't match the installed app:\n$mismatch\n\n" +
            permissionSummary(granted) + "\nOnly continue if you trust this repository."

    /**
     * The "What's new" bullets: the newest [limit] commit subjects, then how many commits are not
     * shown. Empty unless the new commit is ahead of the installed one.
     */
    fun whatsNew(comparison: Comparison?, limit: Int = 10): List<String> {
        if (comparison?.status != "ahead") return emptyList()
        val shown = comparison.subjects.take(limit)
        val more = comparison.totalCommits - shown.size
        return shown.map { "• $it" } + if (more > 0) listOf("+ $more more ${if (more == 1) "commit" else "commits"}") else emptyList()
    }

    /**
     * Why commits are not listed, when an installed app's new commit is not simply ahead of the
     * installed one; null when there is nothing to explain. [otherRepository] is true when the update
     * comes from a different repository than the installed version, which GitHub cannot compare.
     */
    fun historyNote(comparison: Comparison?, otherRepository: Boolean): String? = when {
        otherRepository -> "Commits aren't listed because this update comes from a different repository than the installed version."
        comparison?.status == "behind" -> "⚠ This commit is ${comparison.behindBy} ${if (comparison.behindBy == 1) "commit" else "commits"} behind the installed version, so installing it goes back."
        comparison?.status == "diverged" -> "The installed version isn't part of this branch's history, so the commits in between can't be listed."
        else -> null
    }

    data class UpdateLabels(val progress: String, val before: String, val after: String)

    /**
     * The progress title and the two Version history entries of an update, by app name and version.
     * Commits stay out of them: history entries carry their own time.
     */
    fun updateLabels(appName: String?, version: String): UpdateLabels {
        val app = appName ?: "app"
        return UpdateLabels("Updating $app to $version", "Before update to $app $version", "Updated to $app $version")
    }

    /** Detail of the update progress while package files download. */
    fun downloadProgress(done: Int, total: Int) = "Downloading files ($done of $total)…"

    /** One line of [identityWarning]: the old and new source, naming the repository only when it changes. */
    fun sourceChange(old: GitHubSource, new: GitHubSource): String =
        if (old.owner == new.owner && old.repository == new.repository) "Branch: ${old.ref} → ${new.ref}"
        else "Repository: ${old.display} → ${new.display}"

    fun publisherChange(old: String, new: String) = "Publisher ID: $old → $new"

    /** Plain-language reason a check failed, followed by the source it tried. */
    fun checkFailure(e: Throwable, source: GitHubSource): String {
        val message = e.message.orEmpty()
        val repo = "${source.owner}/${source.repository}"
        val reason = when {
            "(422)" in message || "No commit found" in message || "Branch not found" in message -> "Branch \"${source.branch}\" wasn't found in $repo."
            "(404)" in message -> "$repo wasn't found. Check the name; private repositories aren't supported."
            "(403)" in message || "(429)" in message -> "GitHub is limiting requests right now. Try again in a few minutes."
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
                "Couldn't reach GitHub. Check your internet connection."
            message.isNotBlank() -> message
            else -> "Something went wrong while checking GitHub."
        }
        return "$reason\n\n${source.display}"
    }

    /**
     * Compares versions like "1.2.10" > "1.2.9", with an optional pre-release suffix that sorts before
     * its release: 1.2.46 < 1.2.47-dev.3 < 1.2.47-dev.10 < 1.2.47. Returns null when either version
     * does not start with dotted numbers.
     */
    fun compareVersions(a: String, b: String): Int? {
        fun parse(value: String): Pair<List<Int>, List<String>>? {
            val text = value.trim().removePrefix("v").substringBefore('+')
            val core = text.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
            val pre = if ('-' in text) text.substringAfter('-').split('.') else emptyList()
            return core to pre
        }
        val (leftCore, leftPre) = parse(a) ?: return null
        val (rightCore, rightPre) = parse(b) ?: return null
        for (i in 0 until maxOf(leftCore.size, rightCore.size)) {
            val diff = leftCore.getOrElse(i) { 0 }.compareTo(rightCore.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        if (leftPre.isEmpty() || rightPre.isEmpty()) return rightPre.size.coerceAtMost(1) - leftPre.size.coerceAtMost(1)
        for (i in 0 until maxOf(leftPre.size, rightPre.size)) {
            val l = leftPre.getOrNull(i) ?: return -1
            val r = rightPre.getOrNull(i) ?: return 1
            val ln = l.toIntOrNull(); val rn = r.toIntOrNull()
            val diff = when {
                ln != null && rn != null -> ln.compareTo(rn)
                ln != null -> -1
                rn != null -> 1
                else -> l.compareTo(r)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    /** True when [incoming] is a lower version than [current]; unknown formats are not called older. */
    fun isOlder(current: String?, incoming: String) = current != null && (compareVersions(incoming, current) ?: 0) < 0

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
