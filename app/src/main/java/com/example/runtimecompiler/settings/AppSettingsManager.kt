package com.example.runtimecompiler.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.webkit.WebViewCompat
import com.example.runtimecompiler.workspace.WorkspaceManager
import java.util.Locale

/**
 * Manages persistent user preferences and settings for AppApp (App²).
 */
class AppSettingsManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "appapp_settings"
        const val KEY_MAX_HISTORY_SNAPSHOTS = "max_history_snapshots"
        const val KEY_EDITOR_FONT_SIZE = "editor_font_size_sp"

        const val DEFAULT_MAX_HISTORY_SNAPSHOTS = 4
        const val DEFAULT_EDITOR_FONT_SIZE_SP = 13.0f

        const val FONT_SIZE_SMALL = 11.0f
        const val FONT_SIZE_NORMAL = 13.0f
        const val FONT_SIZE_LARGE = 16.0f
    }

    var maxHistorySnapshots: Int
        get() = prefs.getInt(KEY_MAX_HISTORY_SNAPSHOTS, DEFAULT_MAX_HISTORY_SNAPSHOTS)
        set(value) {
            prefs.edit().putInt(KEY_MAX_HISTORY_SNAPSHOTS, value).apply()
        }

    var editorFontSizeSp: Float
        get() = prefs.getFloat(KEY_EDITOR_FONT_SIZE, DEFAULT_EDITOR_FONT_SIZE_SP)
        set(value) {
            prefs.edit().putFloat(KEY_EDITOR_FONT_SIZE, value).apply()
        }

    /**
     * Resets all user preferences back to factory defaults.
     */
    fun resetSettingsToDefaults() {
        prefs.edit().clear().apply()
    }

    /**
     * Returns clean App² application version string (e.g. "1.0 (Build 1)").
     */
    fun getAppVersion(): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "${pInfo.versionName} (Build $versionCode)"
        } catch (_: Exception) {
            "1.0"
        }
    }

    /**
     * Returns the installed Chromium WebView engine version number (e.g. "131.0.6778.135").
     */
    fun getWebViewEngineVersion(): String {
        return try {
            val pkg = WebViewCompat.getCurrentWebViewPackage(context)
            pkg?.versionName ?: "System Default"
        } catch (_: Exception) {
            "System Default"
        }
    }

    /**
     * Calculates total file size and file count in the workspace directory.
     */
    fun getWorkspaceStorageSummary(workspaceManager: WorkspaceManager): String {
        return try {
            val files = workspaceManager.listFiles()
            var totalBytes = 0L
            for (f in files) {
                totalBytes += f.size
            }
            val formattedSize = formatBytes(totalBytes)
            "$formattedSize (${files.size} files)"
        } catch (_: Exception) {
            "0 KB (0 files)"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
