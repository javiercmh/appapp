package com.wynh.appapp.workspace

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wynh.appapp.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reads and updates the user-facing identity stored in a subapp manifest. */
class AppIdentityManager(private val context: Context, private val record: SubappRecord) {
    private val workspace = WorkspaceManager(context, workspaceDir = record.workspaceDir)
    private val history = WorkspaceHistoryManager(context, appDirectory = record.directory)

    data class Config(val name: String, val description: String, val iconPath: String?)
    sealed interface IconChange {
        data object Keep : IconChange
        data object Reset : IconChange
        data class Replace(val png: ByteArray) : IconChange
    }

    fun load(): Config {
        val json = runCatching { JSONObject(workspace.readFile("manifest.json")) }.getOrDefault(JSONObject())
        val icons = json.optJSONArray("icons")
        val iconPath = icons?.optJSONObject(0)?.optString("src")?.takeIf { it.isNotBlank() }
            ?: "icon.png".takeIf { workspace.getFile(it).isFile }
        return Config(
            json.optString("name").ifBlank { context.getString(R.string.library_untitled) },
            json.optString("description"),
            iconPath
        )
    }

    fun save(name: String, description: String, iconChange: IconChange): Boolean = synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(record.workspaceDir)) {
        val manifestFile = workspace.getFile("manifest.json")
        val manifestBefore = manifestFile.takeIf(File::isFile)?.readBytes()
        val iconFile = workspace.getFile("icon.png")
        val iconBefore = iconFile.takeIf(File::isFile)?.readBytes()
        return@synchronized try {
            val json = JSONObject(manifestBefore?.toString(Charsets.UTF_8).orEmpty().ifBlank { "{}" })
            json.put("name", name)
            json.put("description", description)
            if (!json.has("short_name")) json.put("short_name", name)
            if (!json.has("version")) json.put("version", "1.0.0")
            if (!json.has("main")) json.put("main", "index.html")
            check(history.saveSnapshot(workspace, "Before app settings change") != null)
            when (iconChange) {
                IconChange.Keep -> Unit
                IconChange.Reset -> {
                    json.remove("icons")
                    workspace.deleteFile("icon.png")
                    removePackagedFile(json, "icon.png")
                }
                is IconChange.Replace -> {
                    iconFile.parentFile?.let { parent -> if (!parent.exists()) check(parent.mkdirs()) }
                    workspace.files.writeBytes("icon.png", iconChange.png)
                    val icons = JSONArray().put(JSONObject()
                        .put("src", "icon.png").put("sizes", "512x512").put("type", "image/png"))
                    json.put("icons", icons)
                    addPackagedFile(json, "icon.png")
                }
            }
            check(workspace.writeFile("manifest.json", json.toString(2).replace("\\/", "/") + "\n"))
            check(history.saveSnapshot(workspace, "App settings changed") != null)
            true
        } catch (_: Exception) {
            runCatching {
                if (manifestBefore == null) manifestFile.delete() else workspace.files.writeBytes("manifest.json", manifestBefore)
                if (iconBefore == null) workspace.deleteFile("icon.png") else workspace.files.writeBytes("icon.png", iconBefore)
            }
            false
        }
    }

    fun requestInstall(): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val config = load()
        val icon = SubappMetadataLoader.load(record.workspaceDir).icon?.let(IconCompat::createWithBitmap) ?: appIcon()
        return runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut(config.name, icon), null)
        }.getOrDefault(false)
    }

    fun refreshPinnedShortcut() {
        val config = load()
        val icon = SubappMetadataLoader.load(record.workspaceDir).icon?.let(IconCompat::createWithBitmap) ?: appIcon()
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut(config.name, icon))) }
    }

    private fun shortcut(name: String, icon: IconCompat) = ShortcutInfoCompat.Builder(context, "appapp_subapp_${record.id}")
        .setShortLabel(name.take(25)).setLongLabel(name).setIcon(icon)
        .setIntent(Intent(context, com.wynh.appapp.SubappRuntimeActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("appapp://subapp/${record.id}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        }).build()

    private fun appIcon(): IconCompat = IconCompat.createWithResource(context, R.mipmap.ic_launcher_round)

    private fun addPackagedFile(json: JSONObject, path: String) {
        val files = json.optJSONArray("files") ?: return
        for (index in 0 until files.length()) if (files.optString(index) == path) return
        files.put(path)
    }

    private fun removePackagedFile(json: JSONObject, path: String) {
        val files = json.optJSONArray("files") ?: return
        for (index in files.length() - 1 downTo 0) if (files.optString(index) == path) files.remove(index)
    }
}
