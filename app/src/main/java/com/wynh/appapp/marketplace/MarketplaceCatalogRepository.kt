package com.wynh.appapp.marketplace

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wynh.appapp.BuildConfig
import com.wynh.appapp.updates.GitHubClient
import com.wynh.appapp.workspace.WorkspaceFiles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Loads the bundled catalog offline on startup and supports manual refresh from GitHub.
 * Never performs background network requests on launch or screen display.
 */
class MarketplaceCatalogRepository(
    private val cacheRoot: File,
    private val bundledVersionCode: Int,
    private val readBundled: () -> String,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val catalogUrl: String = DEFAULT_CATALOG_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        catalogUrl: String = DEFAULT_CATALOG_URL,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        cacheRoot = File(context.filesDir, "marketplace"),
        bundledVersionCode = BuildConfig.VERSION_CODE,
        readBundled = {
            context.assets.open(BUNDLED_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        connect = connect,
        catalogUrl = catalogUrl,
        clock = clock,
    )

    data class LoadedCatalog(
        val apps: List<MarketplaceApp>,
        val syncedFromRemote: Boolean,
        val syncedAtMillis: Long?,
        val iconFiles: Map<String, File>,
    )

    private val cacheFile get() = File(cacheRoot, "catalog-cache.json")
    private val metaFile get() = File(cacheRoot, "catalog-meta.json")
    private val iconsDir get() = File(cacheRoot, "icons")

    fun loadLocal(): LoadedCatalog {
        val cached = runCatching { readValidCache() }.getOrNull()
        if (cached != null) return cached
        val bundled = MarketplaceCatalogParser.parse(readBundled())
        return LoadedCatalog(
            apps = bundled.apps,
            syncedFromRemote = false,
            syncedAtMillis = null,
            iconFiles = emptyMap(),
        )
    }

    fun refreshFromRemote(): LoadedCatalog {
        val client = GitHubClient(
            connect = connect,
            userAgent = USER_AGENT,
            totalLimit = TOTAL_REFRESH_BYTES,
            maxRedirects = 0,
            timeoutMillis = TIMEOUT_MILLIS,
            clock = clock,
        )
        val raw = client.get(catalogUrl, MAX_CATALOG_BYTES).toString(Charsets.UTF_8)
        val parsed = MarketplaceCatalogParser.parse(raw)
        val syncedAt = clock()

        cacheRoot.mkdirs()
        val metaJson = JsonObject().apply {
            addProperty("bundledVersionCode", bundledVersionCode)
            addProperty("syncedAtMillis", syncedAt)
        }.toString()
        WorkspaceFiles.atomicWrite(cacheFile, raw.toByteArray(Charsets.UTF_8))
        WorkspaceFiles.atomicWrite(metaFile, metaJson.toByteArray(Charsets.UTF_8))

        val iconFiles = fetchCustomIcons(client, parsed.apps)
        return LoadedCatalog(
            apps = parsed.apps,
            syncedFromRemote = true,
            syncedAtMillis = syncedAt,
            iconFiles = iconFiles,
        )
    }

    private fun readValidCache(): LoadedCatalog? {
        if (!cacheFile.isFile || !metaFile.isFile) return null
        if (cacheFile.length() > MAX_CATALOG_BYTES || metaFile.length() > MAX_META_BYTES) return null
        val meta = JsonParser.parseString(metaFile.readText(Charsets.UTF_8)).asJsonObject
        val cachedVersionCode = meta.get("bundledVersionCode")?.asInt ?: return null
        if (cachedVersionCode != bundledVersionCode) return null
        val syncedAt = meta.get("syncedAtMillis")?.asLong
        val catalog = MarketplaceCatalogParser.parse(cacheFile.readText(Charsets.UTF_8))
        return LoadedCatalog(
            apps = catalog.apps,
            syncedFromRemote = true,
            syncedAtMillis = syncedAt,
            iconFiles = existingIconFiles(catalog.apps),
        )
    }

    private fun fetchCustomIcons(client: GitHubClient, apps: List<MarketplaceApp>): Map<String, File> {
        val result = mutableMapOf<String, File>()
        for (app in apps) {
            if (app.iconKey in BUILT_IN_ICON_KEYS) continue
            val source = (app.source as? CatalogSource.GitHub)?.source ?: continue
            val target = File(iconsDir, "${app.id}.png")
            val iconUrl = "https://raw.githubusercontent.com/${encode(source.owner)}/${encode(source.repository)}/${encode(source.branch)}/icon.png"
            runCatching {
                val bytes = client.get(iconUrl, MAX_ICON_BYTES)
                if (bytes.isNotEmpty()) {
                    WorkspaceFiles.atomicWrite(target, bytes)
                    result[app.id] = target
                }
            }.onFailure {
                if (target.isFile && target.length() in 1..MAX_ICON_BYTES) {
                    result[app.id] = target
                }
            }
        }
        return result
    }

    private fun existingIconFiles(apps: List<MarketplaceApp>): Map<String, File> {
        if (!iconsDir.isDirectory) return emptyMap()
        return apps.mapNotNull { app ->
            if (app.iconKey in BUILT_IN_ICON_KEYS) return@mapNotNull null
            val file = File(iconsDir, "${app.id}.png")
            if (file.isFile && file.length() in 1..MAX_ICON_BYTES) app.id to file else null
        }.toMap()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val BUNDLED_ASSET_PATH = "catalog/catalog.json"
        const val DEFAULT_CATALOG_URL =
            "https://raw.githubusercontent.com/javiercmh/appapp/main/app/src/main/assets/catalog/catalog.json"
        val BUILT_IN_ICON_KEYS = setOf("lingomage", "working_hours", "b_log", "starter_demo")
        private const val USER_AGENT = "AppApp-Catalog"
        private const val MAX_CATALOG_BYTES = 256L * 1024
        private const val MAX_META_BYTES = 64L * 1024
        private const val MAX_ICON_BYTES = 512L * 1024
        private const val TOTAL_REFRESH_BYTES = 4L * 1024 * 1024
        private const val TIMEOUT_MILLIS = 30_000L
    }
}
