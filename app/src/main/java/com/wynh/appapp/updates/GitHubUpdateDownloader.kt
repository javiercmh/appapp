package com.wynh.appapp.updates

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One file in a GitHub comparison; [previousPath] is set for a rename. */
data class ChangedFile(val path: String, val status: String, val previousPath: String? = null)

/**
 * What GitHub reports between the installed commit and the newest one. [status] is "ahead",
 * "identical", "behind" or "diverged". [subjects] are commit subject lines, newest first, out of
 * [totalCommits]. GitHub lists at most [MAX_FILES] files, so a list that long may be incomplete.
 */
data class Comparison(val status: String, val aheadBy: Int, val behindBy: Int, val totalCommits: Int, val subjects: List<String>, val files: List<ChangedFile>) {
    val filesComplete: Boolean get() = files.size < MAX_FILES
    /** Every path the comparison touches, including the old names of renamed files. */
    val touchedPaths: Set<String> get() = files.flatMap { listOfNotNull(it.path, it.previousPath) }.toSet()

    companion object {
        const val MAX_FILES = 300

        fun parse(text: String): Comparison {
            val root = JsonParser.parseString(text).asJsonObject
            fun int(key: String) = root.get(key)?.takeUnless { it.isJsonNull }?.asInt ?: 0
            // GitHub lists commits oldest first.
            val subjects = root.getAsJsonArray("commits")?.mapNotNull { item ->
                (item as? JsonObject)?.getAsJsonObject("commit")?.get("message")?.asString?.lineSequence()?.first()?.trim()?.take(120)?.takeIf(String::isNotEmpty)
            }.orEmpty().asReversed()
            val files = root.getAsJsonArray("files")?.map { item ->
                val file = item.asJsonObject
                ChangedFile(file.get("filename").asString, file.get("status")?.asString ?: "modified",
                    file.get("previous_filename")?.takeUnless { it.isJsonNull }?.asString)
            }.orEmpty()
            val status = root.get("status").asString
            require(status in setOf("ahead", "identical", "behind", "diverged")) { "GitHub returned an unknown comparison" }
            return Comparison(status, int("ahead_by"), int("behind_by"), maxOf(int("total_commits"), subjects.size), subjects, files)
        }
    }
}

class GitHubUpdateDownloader(private val cache: File, private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    data class Download(val pkg: UpdatePackage, val commit: String, val directory: File)

    /** A client for one small API request: bounded in size and time, unlike a full download's session. */
    private fun small() = GitHubClient(connect, USER_AGENT, MAX_BYTES, timeoutMillis = SMALL_TIMEOUT)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun repo(source: GitHubSource) = "${source.owner}/${source.repository}"

    /** Branch names of a public repository, in GitHub's order (up to 100). */
    fun branches(owner: String, repository: String): List<String> {
        GitHubSource(owner, repository, "main") // validates the names before they reach the URL
        val text = small().get("https://api.github.com/repos/$owner/$repository/branches?per_page=100", 1024 * 1024).toString(Charsets.UTF_8)
        return JsonParser.parseString(text).asJsonArray.mapNotNull { it.asJsonObject.get("name")?.asString?.takeIf(String::isNotBlank) }
    }

    /** The newest commit of the followed branch: one small request, used to skip work when nothing moved. */
    fun latestCommit(source: GitHubSource): String {
        val sha = small().get("https://api.github.com/repos/${repo(source)}/commits/${encode(source.branch)}", 4096, accept = "application/vnd.github.sha")
            .toString(Charsets.UTF_8).trim()
        requireCommit(sha)
        return sha
    }

    /**
     * Reads only the package manifest at [commit] and checks it against this host, so the review can
     * name the version, publisher and files before anything else is downloaded.
     */
    fun manifest(source: GitHubSource, commit: String, hostCompatibilityLevel: Long): UpdatePackage {
        requireCommit(commit)
        val text = small().get("https://raw.githubusercontent.com/${repo(source)}/$commit/manifest.json", MANIFEST_LIMIT).toString(Charsets.UTF_8)
        return UpdatePackage.parse(text).also { checkHost(it, hostCompatibilityLevel) }
    }

    /**
     * Commits and changed files between [base] and [head] in [source]'s repository, in one request.
     * A very large response (GitHub includes patches) fails rather than being read; callers then fall
     * back to downloading and comparing the package.
     */
    fun compare(source: GitHubSource, base: String, head: String): Comparison {
        requireCommit(base); requireCommit(head)
        val text = small().get("https://api.github.com/repos/${repo(source)}/compare/$base...$head", COMPARE_LIMIT).toString(Charsets.UTF_8)
        return Comparison.parse(text)
    }

    /**
     * Downloads the package at [knownCommit], or at the branch's newest commit when it is null.
     * [reuse] may fill a package file from somewhere known to hold the publisher's exact content (it
     * copies into the given target and returns true); every other file is fetched, a few at a time.
     * [progress] receives (files ready, total files), possibly from several threads.
     */
    fun download(
        source: GitHubSource, hostCompatibilityLevel: Long, knownCommit: String? = null,
        reuse: (path: String, target: File) -> Boolean = { _, _ -> false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Download {
        val client = GitHubClient(connect, USER_AGENT, MAX_BYTES, timeoutMillis = 5 * 60_000)
        val commit = knownCommit ?: JsonParser.parseString(client.get("https://api.github.com/repos/${repo(source)}/commits/${encode(source.branch)}", 1024 * 1024).toString(Charsets.UTF_8)).asJsonObject.get("sha").asString
        requireCommit(commit)
        val root = "https://raw.githubusercontent.com/${repo(source)}/$commit/"
        val manifest = client.get(root + "manifest.json", MANIFEST_LIMIT)
        val pkg = UpdatePackage.parse(manifest.toString(Charsets.UTF_8))
        // The user's chosen repository and branch decide where updates come from. The manifest's
        // github block only suggests a source when linking, so a dev branch or a fork can keep it.
        checkHost(pkg, hostCompatibilityLevel)
        val directory = File(cache, "$STAGING_PREFIX${java.util.UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            File(directory, "manifest.json").writeBytes(manifest)
            val paths = (pkg.managed + pkg.seeds - "manifest.json").toList()
            fun target(path: String) = File(directory, safePath(path)).also { check(it.parentFile!!.mkdirs() || it.parentFile!!.isDirectory) }
            val total = paths.size + 1
            val done = AtomicInteger(1)
            val pending = paths.filterNot { path -> runCatching { reuse(path, target(path)) }.getOrDefault(false).also { if (it) done.incrementAndGet() } }
            progress(done.get(), total)
            fetchAll(pending, threads = PARALLEL) { path ->
                val content = client.get(root + path.split('/').joinToString("/", transform = ::encode), 8L * 1024 * 1024)
                target(path).writeBytes(content)
                progress(done.incrementAndGet(), total)
            }
            return Download(pkg, commit, directory)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }

    /**
     * Runs [fetch] for every path on a small pool. The first failure stops new work, waits for the
     * requests already running to end (so nothing writes into a directory being deleted) and is rethrown.
     */
    private fun fetchAll(paths: List<String>, threads: Int, fetch: (String) -> Unit) {
        if (paths.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(threads, paths.size))
        val failed = AtomicBoolean(false)
        try {
            val futures = paths.map { path -> pool.submit(java.util.concurrent.Callable {
                if (!failed.get()) try { fetch(path) } catch (e: Throwable) { failed.set(true); throw e }
            }) }
            for (future in futures) {
                try { future.get() } catch (e: ExecutionException) { throw (e.cause as? Exception) ?: e }
            }
        } finally {
            pool.shutdown()
            if (!pool.awaitTermination(2, TimeUnit.MINUTES)) pool.shutdownNow()
        }
    }

    private fun requireCommit(sha: String) = require(sha.matches(Regex("[a-f0-9]{40}"))) { "GitHub returned an invalid commit" }
    private fun checkHost(pkg: UpdatePackage, level: Long) = require(pkg.minimumHostVersion <= level) { incompatibleHostMessage(pkg.minimumHostVersion, level) }

    companion object {
        private const val USER_AGENT = "AppApp-Updater"
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val MANIFEST_LIMIT = 256L * 1024
        private const val COMPARE_LIMIT = 8L * 1024 * 1024
        private const val SMALL_TIMEOUT = 60_000L
        private const val PARALLEL = 4
        private const val STAGING_PREFIX = "update-"

        /**
         * Removes staged downloads left behind when the process ended mid-check or mid-update. Call
         * only at process start, before any review or update of this process could own one.
         */
        fun sweepStaged(cache: File) {
            cache.listFiles { file -> file.isDirectory && file.name.startsWith(STAGING_PREFIX) }?.forEach { it.deleteRecursively() }
        }
    }
}
