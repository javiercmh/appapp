package com.wynh.appapp.updates

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Only metadata is fetched here; checking never stages or installs a package. */
class VersionUpdateChecker(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    data class Release(val version: String, val url: String, val devBuild: Long? = null) {
        fun isNewer(installed: String, installedBuild: Long, dev: Boolean): Boolean =
            if (dev) devBuild != null && devBuild > installedBuild
            else (UpdateReviewCopy.compareVersions(version, installed) ?: error("Couldn't compare App² versions")) > 0
    }
    data class PublishedVersion(val source: GitHubSource, val commit: String, val version: String)
    data class PublishedPackage(val published: PublishedVersion, val pkg: UpdatePackage?)

    sealed class SubappCheckResult {
        data object Unlinked : SubappCheckResult()
        data object UpToDate : SubappCheckResult()
        data class Available(val version: PublishedVersion) : SubappCheckResult()
    }

    private fun get(url: String, limit: Long = 256 * 1024): String =
        GitHubClient(connect, "AppApp-VersionCheck", limit, timeoutMillis = 30_000)
            .get(url, limit, "application/vnd.github+json").toString(Charsets.UTF_8)

    fun hostRelease(dev: Boolean): Release? {
        val base = "https://api.github.com/repos/javiercmh/appapp/releases"
        if (!dev) return parseRelease(get("$base/latest"), false)
        // Dev releases have an independent, increasing build counter; API ordering is not version ordering.
        var newest: Release? = null
        for (page in 1..10) {
            val items = JsonParser.parseString(get("$base?per_page=100&page=$page", 2 * 1024 * 1024)).asJsonArray
            for (item in items) {
                val release = parseRelease(item.toString(), true) ?: continue
                if (newest == null || release.devBuild!! > newest.devBuild!!) newest = release
            }
            if (items.size() < 100) return newest
        }
        error("Too many releases to determine the latest Dev build")
    }

    fun subappPackage(source: GitHubSource): PublishedPackage {
        val branch = URLEncoder.encode(source.branch, "UTF-8").replace("+", "%20")
        val commit = JsonParser.parseString(get("https://api.github.com/repos/${source.owner}/${source.repository}/commits/$branch"))
            .asJsonObject.get("sha").asString
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "GitHub returned an invalid commit" }
        val manifest = get("https://raw.githubusercontent.com/${source.owner}/${source.repository}/$commit/manifest.json")
        val version = manifestVersion(manifest)
        val pkg = runCatching { UpdatePackage.parse(manifest) }.getOrNull()
        return PublishedPackage(PublishedVersion(source, commit, version), pkg)
    }

    fun subapp(source: GitHubSource): PublishedVersion = subappPackage(source).published

    fun compare(source: GitHubSource, base: String, head: String): Comparison {
        require(base.matches(Regex("[a-f0-9]{40}")) && head.matches(Regex("[a-f0-9]{40}"))) { "Invalid commit" }
        val text = get("https://api.github.com/repos/${source.owner}/${source.repository}/compare/$base...$head", COMPARE_LIMIT)
        return Comparison.parse(text)
    }

    /**
     * Checks linked subapps in one pass, sharing branch lookups and commit comparisons across apps
     * that follow the same source.
     */
    inner class SubappCheckSession(private val availability: UpdateAvailability? = null) {
        private val lookups = mutableMapOf<GitHubSource, Result<PublishedPackage>>()
        private val comparisons = mutableMapOf<Triple<GitHubSource, String, String>, Result<Comparison>>()

        fun check(appId: String, updater: WorkspaceUpdater): SubappCheckResult {
            val installed = updater.installed() ?: return SubappCheckResult.Unlinked
            val source = installed.source
            val checked = lookups.getOrPut(source) { runCatching { subappPackage(source) } }.getOrThrow()
            val published = checked.published
            val cmp = compareToInstalled(published.version, updater.currentVersion())
            if (cmp > 0) return SubappCheckResult.Available(published)
            if (cmp < 0) return SubappCheckResult.UpToDate
            if (published.commit == installed.commit || published.commit == availability?.knownUnchanged(appId, source)) {
                return SubappCheckResult.UpToDate
            }
            val pkg = checked.pkg ?: return SubappCheckResult.UpToDate
            val comparison = comparisons.getOrPut(Triple(source, installed.commit, published.commit)) {
                runCatching { compare(source, installed.commit, published.commit) }
            }.getOrThrow()
            val plan = updater.plan(pkg, comparison, source)
            if (plan != null) {
                if (plan.unchanged) {
                    availability?.recordUnchanged(appId, source, published.commit)
                    return SubappCheckResult.UpToDate
                }
                return SubappCheckResult.Available(published)
            }
            return if (comparison.status == "ahead" || comparison.status == "diverged") SubappCheckResult.Available(published)
            else SubappCheckResult.UpToDate
        }
    }

    companion object {
        private const val COMPARE_LIMIT = 8L * 1024 * 1024

        internal fun compareToInstalled(available: String, installed: String?): Int {
            require(installed != null) { "Missing installed version" }
            return UpdateReviewCopy.compareVersions(available, installed) ?: error("Couldn't compare versions")
        }

        fun newer(available: String, installed: String?): Boolean = compareToInstalled(available, installed) > 0

        /**
         * True when [availableVersion] at [availableCommit] is an applicable update for an app
         * currently at [installedVersion] / [installedCommit]: either a strictly newer version, or
         * the same version at a different commit when the installed commit is known.
         */
        fun isApplicable(availableVersion: String, availableCommit: String, installedVersion: String?, installedCommit: String?): Boolean {
            val cmp = compareToInstalled(availableVersion, installedVersion)
            return cmp > 0 || (cmp == 0 && installedCommit != null && availableCommit != installedCommit)
        }

        fun isApplicable(published: PublishedVersion, installedVersion: String?, installedCommit: String?): Boolean =
            isApplicable(published.version, published.commit, installedVersion, installedCommit)

        internal fun manifestVersion(text: String): String {
            val value = JsonParser.parseString(text).asJsonObject.get("version")
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "Missing version" }
            val version = value.asString
            require(version.length <= 100 && UpdateReviewCopy.compareVersions(version, version) != null) { "Invalid version" }
            return version
        }

        internal fun parseRelease(text: String, dev: Boolean): Release? {
            val obj = JsonParser.parseString(text).asJsonObject
            if (obj.get("draft").asBoolean || obj.get("prerelease").asBoolean != dev) return null
            val tag = obj.get("tag_name").asString
            if (dev) {
                val build = Regex("dev-([0-9]+)").matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
                return Release(tag, "https://github.com/javiercmh/appapp/releases/tag/$tag", build)
            }
            require(Regex("v?[0-9]+(?:\\.[0-9]+)*").matches(tag) && UpdateReviewCopy.compareVersions(tag, tag) != null) { "Unrecognized release version" }
            // Construct a trusted destination; never open an arbitrary response URL.
            return Release(tag.removePrefix("v"), "https://github.com/javiercmh/appapp/releases/tag/$tag")
        }
    }
}
