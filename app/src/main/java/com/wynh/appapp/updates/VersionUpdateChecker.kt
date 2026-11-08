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

    fun subapp(source: GitHubSource): PublishedVersion {
        val branch = URLEncoder.encode(source.branch, "UTF-8").replace("+", "%20")
        val commit = JsonParser.parseString(get("https://api.github.com/repos/${source.owner}/${source.repository}/commits/$branch"))
            .asJsonObject.get("sha").asString
        require(commit.matches(Regex("[a-f0-9]{40}"))) { "GitHub returned an invalid commit" }
        val version = manifestVersion(get("https://raw.githubusercontent.com/${source.owner}/${source.repository}/$commit/manifest.json"))
        return PublishedVersion(source, commit, version)
    }

    companion object {
        fun newer(available: String, installed: String?): Boolean {
            require(installed != null) { "Missing installed version" }
            return (UpdateReviewCopy.compareVersions(available, installed) ?: error("Couldn't compare versions")) > 0
        }

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
