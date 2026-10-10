package com.wynh.appapp.updates

import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.SubappSessionRegistry
import com.wynh.appapp.ai.AppAiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shared native review flow for library, editor and web callers. */
class UpdateController(private val activity: AppCompatActivity, private val record: SubappRecord, private val finished: () -> Unit = {}) {
    private val updater = WorkspaceUpdater(record.directory)
    private val ai get() = (activity.application as AppAiApplication).ai
    private val hostVersion get() = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
    private fun error(e: Exception) { if (!activity.isFinishing) MaterialAlertDialogBuilder(activity).setTitle("Update unavailable").setMessage(e.message ?: "Update failed").setPositiveButton("Close", null).show() }

    fun open() {
        if (ai.hasRequest(record.id)) { error(IllegalStateException("Finish and save or cancel the AI request before updating.")); return }
        try {
            val old = updater.installed()
            if (old != null) {
                val builder = MaterialAlertDialogBuilder(activity).setTitle("Update app")
                    .setMessage("Installed ${old.version}\n${old.source.display}\nCommit ${old.commit.take(12)}")
                    .setPositiveButton("Check GitHub") { _, _ -> check(old.source) }.setNegativeButton("Cancel", null)
                if (File(record.directory, "update-backup/state.json").isFile) builder.setNeutralButton("Roll back code") { _, _ -> confirmRollback() }
                builder.show()
            } else {
                val suggested = runCatching { UpdatePackage.parse(File(record.workspaceDir, "manifest.json").readText()).source }.getOrNull()
                val repo = EditText(activity).apply { hint = "owner/repository"; setText(suggested?.let { "${it.owner}/${it.repository}" } ?: "") }
                val branch = EditText(activity).apply { hint = "Branch"; setText(suggested?.branch ?: "main") }
                val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 8); addView(repo); addView(branch) }
                val dialog = MaterialAlertDialogBuilder(activity).setTitle("Link this app to GitHub")
                    .setMessage("Choose the publisher repository for this app. No files change until you review and confirm the update.")
                    .setView(form).setPositiveButton("Check", null).setNegativeButton("Cancel", null).show()
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    try { val parts = repo.text.toString().trim().split('/'); require(parts.size == 2) { "Enter owner/repository" }; val source = GitHubSource(parts[0], parts[1], branch.text.toString().trim()); dialog.dismiss(); check(source) }
                    catch (e: Exception) { repo.error = e.message }
                }
            }
        } catch (e: Exception) { error(e) }
    }

    private fun check(source: GitHubSource) {
        val progress = MaterialAlertDialogBuilder(activity).setTitle("Checking GitHub…").setMessage(source.display).setCancelable(false).show()
        activity.lifecycleScope.launch {
            var download: GitHubUpdateDownloader.Download? = null
            try {
                download = withContext(Dispatchers.IO) { GitHubUpdateDownloader(activity.cacheDir).download(source, hostVersion) }
                val value = download
                val (preview, reviewed) = withContext(Dispatchers.IO) {
                    synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                        updater.preview(value.pkg, value.directory) to updater.reviewedFiles(value.pkg)
                    }
                }
                progress.dismiss()
                val text = buildString {
                    append("${value.pkg.version} · ${value.commit.take(12)}\n${source.display}\n")
                    if (preview.adoption) append("\nLink publisher identity: ${value.pkg.appId}\n")
                    append("\nUser records and unowned files are preserved.\n")
                    fun section(label: String, files: List<String>) { if (files.isNotEmpty()) append("\n$label:\n${files.joinToString("\n")}\n") }
                    section("Added", preview.added); section("Replaced", preview.changed); section("Removed", preview.removed)
                    section(if (preview.adoption) "Existing files to adopt and replace" else "Locally edited or missing code", preview.edited)
                    append("\nA full recovery backup will be created.")
                }
                MaterialAlertDialogBuilder(activity).setTitle("Review app update").setMessage(text)
                    .setPositiveButton(if (preview.adoption) "Link and update" else "Update") { _, _ ->
                        applyUpdate { updater.install(value.pkg, value.directory, value.commit, hostVersion, reviewed) }
                        value.directory.deleteRecursively()
                    }.setNegativeButton("Cancel", null)
                    .setOnDismissListener { value.directory.deleteRecursively() }.show()
            } catch (e: Exception) { progress.dismiss(); download?.directory?.deleteRecursively(); error(e) }
        }
    }

    private fun confirmRollback() {
        MaterialAlertDialogBuilder(activity).setTitle("Roll back app code?")
            .setMessage("Restore code from before the last update. Current stories, settings and other unowned files remain unchanged. Locally edited managed code will be replaced.")
            .setPositiveButton("Roll back") { _, _ -> applyUpdate { updater.rollback() } }.setNegativeButton("Cancel", null).show()
    }

    private fun applyUpdate(operation: () -> Unit) {
        try {
            require(!ai.hasRequest(record.id)) { "Finish and save or cancel the AI request before updating." }
            // Keep the UI thread occupied during the short local transaction so editors cannot race it.
            SubappSessionRegistry.pause(record.id)
            try { operation() } finally { SubappSessionRegistry.resume(record.id) }
            Toast.makeText(activity, "App code updated; user files preserved", Toast.LENGTH_LONG).show()
            finished()
        } catch (e: Exception) { error(e) }
    }
}
