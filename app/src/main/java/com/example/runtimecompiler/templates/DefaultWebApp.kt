package com.example.runtimecompiler.templates

import android.content.Context
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Manages starter template files loaded dynamically from standalone assets
 * (`app/src/main/assets/starter_template/`).
 */
object DefaultWebApp {

    const val STARTER_TEMPLATE_DIR = "starter_template"

    /** Bumped whenever the shipped starter template changes shape. See [PRISTINE_HASHES]. */
    const val TEMPLATE_VERSION = 3

    val CORE_TEMPLATE_FILES = listOf(
        "index.html",
        "style.css",
        "app.js",
        "bridge.js",
        "store.js",
        "ui.js",
        "manifest.json",
        "AGENTS.md",
        "entries.json"
    )

    /** Files the previous template generations shipped that v3 no longer uses. */
    val OBSOLETE_TEMPLATE_FILES = listOf("saved_items.json")

    /**
     * SHA-256 of every starter file as shipped by an earlier template generation, keyed by file
     * name. Used to decide whether a workspace is untouched and therefore safe to upgrade in place.
     *
     * Three generations are covered:
     *  - v0: the hardcoded `DEFAULT_*` constants that originally shipped.
     *  - v1: the initial asset files on development builds.
     *  - v2: the My Day template files before directory and tip modal updates.
     */
    private val PRISTINE_HASHES: Map<String, Set<String>> = mapOf(
        "index.html" to setOf(
            "5fc769705da24f45de2e9d070119be539e6a3fcc63b3993b6a24d2338c92348a",
            "fe38e68f0daeb82007c301254031cc599464880f1a5ae317b7a09074baa3c667",
            "346107373e6079c1d492aec392878cbb23f03d7f99695a8b5a72d2865d2d51ff"
        ),
        "style.css" to setOf(
            "f510ae3ceb8c770d8dd5e5437ab8b08fa10d5c94269f30a8ed5d5868c70adfe6",
            "7b9084f80c919799360cd6b299dfea75ceae56f1d44883d4af83520f98dd0432",
            "3578af56a3c5701accb5262a0dea93b8bd4427a92dd64430a81d657a81b9879c"
        ),
        "app.js" to setOf(
            "364dbdea82f75c90e56ce667a31383ccdbe7f03de123e6f00837613be58d45b8",
            "787f9662a61aa9eb073b6e268fe6356853da429e26892aec006689d844869c17",
            "d31ef0c6ac3d86ef04f93d13f8dc10bd2daebfec11bebcbfc024f63535623255"
        ),
        "ui.js" to setOf(
            "e55099f046af50e05d5f84d2d472ec2be097820b2466c4172faa2afa575ffe78"
        ),
        "saved_items.json" to setOf(
            "c769919e5c633ee2e294236cc1e8448238c26e56f372b614a9a75a02d2174383",
            "515c4579336ca85a566bbe5c86efaa8f5d655a4ddb8ccc0c86f5d432d52c3410"
        )
    )

    fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** True if [content] matches any previously shipped version of [fileName]. */
    fun isPristine(fileName: String, content: String): Boolean =
        PRISTINE_HASHES[fileName]?.contains(sha256(content)) == true

    private val fileCache = mutableMapOf<String, String>()

    /**
     * Loads all starter template files from the assets directory (recursively).
     */
    @Synchronized
    fun getStarterFiles(context: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            fun walkAssets(dir: String, prefix: String = "") {
                val list = context.assets.list(dir) ?: return
                for (name in list) {
                    val subPath = if (dir.isEmpty()) name else "$dir/$name"
                    val relativeName = if (prefix.isEmpty()) name else "$prefix/$name"
                    val children = context.assets.list(subPath)
                    if (children != null && children.isNotEmpty() && !name.contains('.')) {
                        walkAssets(subPath, relativeName)
                    } else {
                        val content = getStarterFile(context, relativeName)
                        if (content.isNotEmpty()) {
                            result[relativeName] = content
                        }
                    }
                }
            }
            walkAssets(STARTER_TEMPLATE_DIR)
        } catch (_: Exception) {
            for (fileName in CORE_TEMPLATE_FILES) {
                val content = getStarterFile(context, fileName)
                if (content.isNotEmpty()) {
                    result[fileName] = content
                }
            }
        }
        if (result.isEmpty()) {
            for (fileName in CORE_TEMPLATE_FILES) {
                val content = getStarterFile(context, fileName)
                if (content.isNotEmpty()) {
                    result[fileName] = content
                }
            }
        }
        return result
    }

    /**
     * Loads a single starter template file from assets.
     */
    @Synchronized
    fun getStarterFile(context: Context, fileName: String): String {
        fileCache[fileName]?.let { return it }

        return try {
            val path = "$STARTER_TEMPLATE_DIR/$fileName"
            val content = context.assets.open(path).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            fileCache[fileName] = content
            content
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Clears in-memory cache of starter files.
     */
    @Synchronized
    fun clearCache() {
        fileCache.clear()
    }
}
