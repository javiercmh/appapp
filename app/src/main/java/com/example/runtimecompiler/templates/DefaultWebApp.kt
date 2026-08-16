package com.example.runtimecompiler.templates

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Manages starter template files loaded dynamically from standalone assets
 * (`app/src/main/assets/starter_template/`).
 */
object DefaultWebApp {

    const val STARTER_TEMPLATE_DIR = "starter_template"

    val CORE_TEMPLATE_FILES = listOf(
        "index.html",
        "css/style.css",
        "js/app.js",
        "js/bridge.js",
        "js/store.js",
        "js/ui.js",
        "manifest.json",
        "AGENTS.md",
        "data/entries.json"
    )

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
