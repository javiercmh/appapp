package com.wynh.appapp.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.webkit.WebViewCompat

/**
 * Manages persistent user preferences and settings for AppApp (App²).
 */
class AppSettingsManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "appapp_settings"
        const val KEY_MAX_HISTORY_SNAPSHOTS = "max_history_snapshots"
        const val KEY_LAST_BACKUP_AT = "last_backup_at_millis"

        const val DEFAULT_MAX_HISTORY_SNAPSHOTS = 4
    }

    var maxHistorySnapshots: Int
        get() = prefs.getInt(KEY_MAX_HISTORY_SNAPSHOTS, DEFAULT_MAX_HISTORY_SNAPSHOTS)
        set(value) {
            prefs.edit().putInt(KEY_MAX_HISTORY_SNAPSHOTS, value).apply()
        }

    var lastBackupAtMillis: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP_AT, value).apply()
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
}
