package com.wynh.appapp.marketplace

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wynh.appapp.updates.GitHubSource
import java.util.Locale

sealed class CatalogSource {
    data class GitHub(val source: GitHubSource, val publisherAppId: String?) : CatalogSource() {
        val repoUrl: String get() = "https://github.com/${source.owner}/${source.repository}"
        val repoKey: String get() = "${source.owner}/${source.repository}".lowercase(Locale.ROOT)
    }

    data object BundledStarter : CatalogSource()
}

enum class CatalogCapability(val wire: String) {
    AI("ai"),
    SPEECH("speech"),
    REMINDERS("reminders"),
    INTERNET("internet"),
    TEMPLATE("template"),
    FILES("files"),
    SHARE("share");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(value: String): CatalogCapability? = byWire[value.trim().lowercase(Locale.ROOT)]
    }
}

data class MarketplaceApp(
    val id: String,
    val name: String,
    val author: String?,
    val category: String,
    val license: String,
    val summary: String,
    val description: String,
    val capabilities: List<CatalogCapability>,
    val offlineOnly: Boolean,
    val minHostCompatibility: Long,
    val iconKey: String,
    val source: CatalogSource,
)

data class MarketplaceCatalog(
    val schemaVersion: Int,
    val apps: List<MarketplaceApp>,
)

object MarketplaceCatalogParser {
    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MAX_APPS = 100
    private val ID_REGEX = Regex("[a-z0-9][a-z0-9_-]{1,63}")
    private val ICON_KEY_REGEX = Regex("[a-z0-9_-]{1,64}")
    private val PUBLISHER_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,127}")

    fun parse(text: String): MarketplaceCatalog {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid catalog JSON") }
        val schemaVersion = root.int("schemaVersion")
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported catalog schema version: $schemaVersion"
        }
        val items = root.get("apps")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw IllegalArgumentException("Catalog is missing apps list")
        require(items.size() in 1..MAX_APPS) { "Catalog must list between 1 and $MAX_APPS apps" }

        val seenIds = mutableSetOf<String>()
        val apps = items.map { element ->
            require(element.isJsonObject) { "Invalid catalog entry" }
            parseApp(element.asJsonObject, seenIds)
        }
        return MarketplaceCatalog(schemaVersion, apps)
    }

    private fun parseApp(obj: JsonObject, seenIds: MutableSet<String>): MarketplaceApp {
        val id = obj.requiredString("id", 64)
        require(ID_REGEX.matches(id)) { "Invalid catalog app ID: $id" }
        require(seenIds.add(id)) { "Duplicate catalog app ID: $id" }

        val name = obj.requiredString("name", 80)
        val author = obj.optionalString("author", 60)
        val category = obj.requiredString("category", 40)
        val license = obj.requiredString("license", 40)
        val summary = obj.requiredString("summary", 200)
        val description = obj.requiredString("description", 2000)

        val capsElement = obj.get("capabilities")
        val capabilities = if (capsElement == null || capsElement.isJsonNull) {
            emptyList()
        } else {
            require(capsElement.isJsonArray) { "Invalid capabilities list for $id" }
            capsElement.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.let(CatalogCapability::fromWire)
            }.distinct()
        }

        val offlineElement = obj.get("offlineOnly")
        require(offlineElement != null && offlineElement.isJsonPrimitive && offlineElement.asJsonPrimitive.isBoolean) {
            "Missing offlineOnly flag for $id"
        }
        val offlineOnly = offlineElement.asBoolean

        val minHostCompatibility = obj.long("minHostCompatibility")
        require(minHostCompatibility >= 1L) { "Invalid host compatibility level for $id" }

        val iconKey = obj.requiredString("iconKey", 64)
        require(ICON_KEY_REGEX.matches(iconKey)) { "Invalid iconKey for $id" }

        val sourceObj = obj.get("source")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("Missing source for $id")
        val source = parseSource(id, sourceObj)

        return MarketplaceApp(
            id = id,
            name = name,
            author = author,
            category = category,
            license = license,
            summary = summary,
            description = description,
            capabilities = capabilities,
            offlineOnly = offlineOnly,
            minHostCompatibility = minHostCompatibility,
            iconKey = iconKey,
            source = source,
        )
    }

    private fun parseSource(appId: String, obj: JsonObject): CatalogSource {
        return when (val type = obj.requiredString("type", 32)) {
            "bundled_starter" -> CatalogSource.BundledStarter
            "github" -> {
                val owner = obj.requiredString("owner", 100)
                val repository = obj.requiredString("repository", 100)
                val branch = obj.optionalString("branch", 200) ?: "main"
                val publisherAppId = obj.optionalString("publisherAppId", 128)?.also {
                    require(PUBLISHER_ID_REGEX.matches(it)) { "Invalid publisherAppId for $appId" }
                }
                CatalogSource.GitHub(GitHubSource(owner, repository, branch), publisherAppId)
            }
            else -> throw IllegalArgumentException("Unsupported source type '$type' for $appId")
        }
    }

    private fun JsonObject.requiredString(key: String, maxLen: Int): String {
        val el: JsonElement = get(key) ?: throw IllegalArgumentException("Missing $key")
        require(el.isJsonPrimitive && el.asJsonPrimitive.isString) { "Invalid $key" }
        val value = el.asString.trim()
        require(value.isNotEmpty() && value.length <= maxLen) { "Invalid $key length" }
        return value
    }

    private fun JsonObject.optionalString(key: String, maxLen: Int): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        require(el.isJsonPrimitive && el.asJsonPrimitive.isString) { "Invalid $key" }
        val value = el.asString.trim()
        if (value.isEmpty()) return null
        require(value.length <= maxLen) { "Invalid $key length" }
        return value
    }

    private fun JsonObject.int(key: String): Int {
        val el = get(key) ?: throw IllegalArgumentException("Missing $key")
        require(el.isJsonPrimitive && el.asJsonPrimitive.isNumber) { "Invalid $key" }
        return runCatching { el.asInt }.getOrElse { throw IllegalArgumentException("Invalid $key") }
    }

    private fun JsonObject.long(key: String): Long {
        val el = get(key) ?: throw IllegalArgumentException("Missing $key")
        require(el.isJsonPrimitive && el.asJsonPrimitive.isNumber) { "Invalid $key" }
        return runCatching { el.asLong }.getOrElse { throw IllegalArgumentException("Invalid $key") }
    }
}

/** Reads the optional top-level `author` of a subapp manifest: a string or `{"name": ...}`. */
object ManifestAuthorResolver {
    private const val MAX_AUTHOR_LENGTH = 60

    fun fromManifestJson(manifest: JsonObject?): String? {
        val element = manifest?.get("author") ?: return null
        val raw = when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
            element.isJsonObject -> element.asJsonObject.get("name")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
            else -> null
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_AUTHOR_LENGTH)
    }
}
