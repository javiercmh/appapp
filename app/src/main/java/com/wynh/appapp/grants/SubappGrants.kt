package com.wynh.appapp.grants

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.R
import com.wynh.appapp.bridge.NativeStorageBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Process-wide access to per-subapp capability grants, with the side effects of revocation.
 *
 * Grant lifecycle:
 * - Granted only by a native dialog (first AI start, first notification use) or the library's
 *   per-app Permissions dialog. Subapp code cannot write the store.
 * - Revoked from the Permissions dialog. Revoking notifications cancels the subapp's posted
 *   notifications and scheduled alarms; revoking AI cancels its unfinished AI requests.
 * - Cleared when the subapp is deleted ([SubappRepository.delete]).
 * - Reset after a GitHub update installs changed publisher files ([resetAfterCodeChange], called by
 *   UpdateController). ZIP installation and backup restore always create new IDs, which start
 *   without grants.
 */
object SubappGrants {
    private const val PREFS = "subapp_capability_grants"
    @Volatile private var instance: SubappGrantStore? = null

    fun store(context: Context): SubappGrantStore {
        val app = context.applicationContext
        val store = instance ?: synchronized(this) {
            instance ?: SubappGrantStore(PreferencesStorage(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
                .also { instance = it }
        }
        if (!store.migrated()) migrate(app, store)
        return store
    }

    fun isGranted(context: Context, appId: String, capability: SubappCapability): Boolean =
        store(context).isGranted(appId, capability)

    fun setGranted(context: Context, appId: String, capability: SubappCapability, granted: Boolean) {
        if (store(context).set(appId, capability, granted) && !granted) onRevoked(context, appId, setOf(capability))
    }

    /** Deletion cleanup; the caller already cancels notifications and AI requests. */
    fun clear(context: Context, appId: String) { store(context).clear(appId) }

    /** New publisher code must ask again; revocation side effects apply. */
    fun resetAfterCodeChange(context: Context, appId: String) {
        onRevoked(context, appId, store(context).clear(appId))
    }

    private fun onRevoked(context: Context, appId: String, capabilities: Set<SubappCapability>) {
        if (SubappCapability.NOTIFICATIONS in capabilities) NativeStorageBridge.cancelAllForSubapp(context, appId)
        if (SubappCapability.AI in capabilities)
            (context.applicationContext as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelUnfinished(appId)
    }

    /**
     * Runs once the library exists (after the legacy single-workspace migration), so a legacy
     * workspace's migrated reminders are seen. Reads the index file directly to avoid taking the
     * repository lock from inside grant lookups.
     */
    private fun migrate(context: Context, store: SubappGrantStore) {
        val root = File(context.filesDir, "subapps")
        if (!File(root, "migration_complete").isFile) return
        val ids = try {
            val index = File(root, "index.json")
            val array = JSONArray(if (index.isFile) index.readText() else "[]")
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) { return }
        store.migrateExisting(ids) { NativeStorageBridge.hasPersistedAlarms(context, it) }
    }

    /** The subapp's own manifest name, shortened for dialogs. It is publisher-controlled text. */
    fun displayName(context: Context, appId: String): String {
        val name = runCatching {
            JSONObject(File(context.filesDir, "subapps/$appId/workspace/manifest.json").readText()).optString("name")
        }.getOrNull().orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (name.isEmpty()) context.getString(R.string.library_untitled)
        else if (name.length > 60) name.take(60) + "…" else name
    }

    /**
     * Shows an Allow / Don't allow dialog and reports exactly once. Dismissal counts as Don't allow.
     * Reports false immediately when the activity cannot show a dialog.
     */
    fun ask(activity: Activity, title: String, message: String, onResult: (Boolean) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) { onResult(false); return }
        var answered = false
        fun answer(value: Boolean) { if (!answered) { answered = true; onResult(value) } }
        try {
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.permission_allow) { _, _ -> answer(true) }
                .setNegativeButton(R.string.permission_deny) { _, _ -> answer(false) }
                .setOnDismissListener { answer(false) }
                .show()
        } catch (_: Exception) { answer(false) }
    }

    private class PreferencesStorage(private val prefs: SharedPreferences) : GrantStorage {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(values: Map<String, String?>) {
            val editor = prefs.edit()
            values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            check(editor.commit()) { "Could not save permissions" }
        }
    }
}
