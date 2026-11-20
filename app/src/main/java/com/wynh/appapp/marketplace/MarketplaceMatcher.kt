package com.wynh.appapp.marketplace

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wynh.appapp.host.HostCompatibility
import com.wynh.appapp.updates.WorkspaceUpdater
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.WorkspaceFiles
import java.io.File
import java.net.URL
import java.util.Locale

data class InstalledProvenance(
    val recordId: String,
    val publisherAppId: String?,
    val githubRepoKey: String?,
    val catalogId: String?,
    val manifestAuthor: String? = null,
    val installedVersion: String? = null,
)

data class CatalogItemState(
    val app: MarketplaceApp,
    val installedRecords: List<SubappRecord>,
    val updateRecord: SubappRecord?,
    val hostCompatible: Boolean,
    val resolvedAuthor: String? = app.author,
    val installedVersion: String? = null,
)

object MarketplaceMatcher {
    fun inspectInstalled(record: SubappRecord): InstalledProvenance {
        val installedUpdate = runCatching { WorkspaceUpdater(record.directory).installed() }.getOrNull()
        var publisherAppId = installedUpdate?.appId
        var githubRepoKey = installedUpdate?.source?.let { "${it.owner}/${it.repository}".lowercase(Locale.ROOT) }

        val manifestJson = readManifestObject(record.workspaceDir)
        if (publisherAppId == null && manifestJson != null) {
            publisherAppId = runCatching {
                manifestJson.getAsJsonObject("app2Update")?.get("appId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        val provenanceJson = readImportSource(record.directory)
        val catalogId = provenanceJson?.get("catalogId")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() }
        if (githubRepoKey == null && provenanceJson != null) {
            val url = provenanceJson.get("url")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString.orEmpty()
            if (url.isNotBlank()) githubRepoKey = parseGitHubRepoKey(url)
        }

        val manifestAuthor = ManifestAuthorResolver.fromManifestJson(manifestJson)
        val manifestVersion = manifestJson?.get("version")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val installedVersion = manifestVersion ?: installedUpdate?.version?.trim()?.takeIf { it.isNotEmpty() }

        return InstalledProvenance(
            recordId = record.id,
            publisherAppId = publisherAppId,
            githubRepoKey = githubRepoKey,
            catalogId = catalogId,
            manifestAuthor = manifestAuthor,
            installedVersion = installedVersion,
        )
    }

    fun match(
        apps: List<MarketplaceApp>,
        records: List<SubappRecord>,
        provenance: Map<String, InstalledProvenance>,
        updateAvailable: (SubappRecord) -> Boolean,
        hostLevel: Long = HostCompatibility.LEVEL.toLong(),
    ): List<CatalogItemState> {
        return apps.map { app ->
            val matchingRecords = records.filter { record ->
                val info = provenance[record.id] ?: return@filter false
                matches(app, info)
            }
            val matchingProvenances = matchingRecords.mapNotNull { provenance[it.id] }
            // The catalog names the author; an installed manifest only fills in a missing one.
            val resolvedAuthor = app.author ?: matchingProvenances.firstNotNullOfOrNull { it.manifestAuthor }
            val installedVersion = matchingProvenances.firstNotNullOfOrNull { it.installedVersion }
            CatalogItemState(
                app = app,
                installedRecords = matchingRecords,
                updateRecord = matchingRecords.firstOrNull(updateAvailable),
                hostCompatible = app.minHostCompatibility <= hostLevel,
                resolvedAuthor = resolvedAuthor,
                installedVersion = installedVersion,
            )
        }
    }

    /**
     * Whether an installed app came from this catalog entry. Only recorded provenance counts: an
     * update publisher ID, the GitHub repository it was imported from, or the catalog ID written
     * by a Discover install. Workspace contents are never guessed from, because apps people build
     * from the starter keep its files and must not be treated as a catalog copy.
     */
    fun matches(app: MarketplaceApp, info: InstalledProvenance): Boolean {
        return when (val source = app.source) {
            is CatalogSource.GitHub -> {
                (source.publisherAppId != null && info.publisherAppId == source.publisherAppId) ||
                    info.githubRepoKey == source.repoKey ||
                    info.catalogId == app.id
            }
            CatalogSource.BundledStarter -> info.catalogId == app.id
        }
    }

    internal fun parseGitHubRepoKey(rawUrl: String): String? {
        val url = runCatching { URL(rawUrl.trim()) }.getOrNull() ?: return null
        if (!url.host.equals("github.com", ignoreCase = true)) return null
        val parts = url.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return "$owner/$repo".lowercase(Locale.ROOT)
    }

    private fun readManifestObject(workspaceDir: File) = runCatching {
        val text = WorkspaceFiles(workspaceDir).readText("manifest.json", 1024L * 1024)
        JsonParser.parseString(text).asJsonObject
    }.getOrNull()

    private fun readImportSource(appDirectory: File): JsonObject? {
        val file = File(appDirectory, "import-source.json")
        if (!file.isFile || file.length() > 64L * 1024) return null
        return runCatching { JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject }.getOrNull()
    }
}
