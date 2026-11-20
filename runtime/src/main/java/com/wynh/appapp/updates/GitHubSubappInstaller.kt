package com.wynh.appapp.updates

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.wynh.appapp.workspace.GitHubFolderImporter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs a subapp from a public GitHub repository. Repositories that declare `app2Update` in
 * `manifest.json` fetch only their listed managed and seed files and record initial update metadata
 * so future updates work automatically; repositories without `app2Update` fall back to
 * [GitHubFolderImporter].
 */
class GitHubSubappInstaller(
    private val cacheDir: File,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class InstalledPackage(
        val directory: File,
        val commit: String,
        val source: GitHubSource,
        val installedUpdate: InstalledUpdate?,
    )

    fun download(
        source: GitHubSource,
        hostCompatibilityLevel: Long,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): InstalledPackage {
        if (Thread.interrupted()) throw InterruptedException("GitHub import cancelled")
        val downloader = GitHubUpdateDownloader(cacheDir, connect)
        val commit = downloader.latestCommit(source)
        if (Thread.interrupted()) throw InterruptedException("GitHub import cancelled")
        val manifestText = fetchManifestOrNull(source, commit)
        val hasApp2Update = manifestText != null && runCatching {
            val root = JsonParser.parseString(manifestText).asJsonObject
            root.has("app2Update") && root.get("app2Update").isJsonObject
        }.getOrDefault(false)

        if (manifestText != null && hasApp2Update) {
            val pkg = UpdatePackage.parse(manifestText)
            require(pkg.minimumHostVersion <= hostCompatibilityLevel) {
                incompatibleHostMessage(pkg.minimumHostVersion, hostCompatibilityLevel)
            }
            if (Thread.interrupted()) throw InterruptedException("GitHub import cancelled")
            val result = downloader.download(source, hostCompatibilityLevel, knownCommit = commit, progress = progress)
            val hashes = pkg.managed.associateWith { digest(File(result.directory, it)) }
            return InstalledPackage(
                directory = result.directory,
                commit = result.commit,
                source = source,
                installedUpdate = InstalledUpdate(pkg.appId, pkg.version, source, result.commit, hashes, pkg.seeds),
            )
        }

        if (Thread.interrupted()) throw InterruptedException("GitHub import cancelled")
        val imported = GitHubFolderImporter.download(
            cacheDir = cacheDir,
            link = "https://github.com/${source.owner}/${source.repository}/tree/${source.branch}",
            connect = connect,
            clock = clock,
        ).getOrThrow()
        return InstalledPackage(
            directory = imported.directory,
            commit = imported.commit,
            source = source,
            installedUpdate = null,
        )
    }

    private fun fetchManifestOrNull(source: GitHubSource, commit: String): String? {
        val client = GitHubClient(connect, USER_AGENT, MANIFEST_LIMIT, maxRedirects = 0, timeoutMillis = 60_000, clock = clock)
        val url = "https://raw.githubusercontent.com/${source.owner}/${source.repository}/$commit/manifest.json"
        return try {
            client.get(url, MANIFEST_LIMIT).toString(Charsets.UTF_8)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: GitHubHttpException) {
            // 404 means the repository has no root manifest.json and should fall back; surface other failures.
            if (e.status == HttpURLConnection.HTTP_NOT_FOUND) null else throw e
        }
    }

    companion object {
        private const val USER_AGENT = "AppApp-Installer"
        private const val MANIFEST_LIMIT = 256L * 1024
        private val gson = Gson()

        fun serializeUpdateState(installed: InstalledUpdate): String = gson.toJson(installed)
    }
}
