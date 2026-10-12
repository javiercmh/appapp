package com.wynh.appapp.updates

import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.workspace.WorkspaceHistoryManager
import com.wynh.appapp.workspace.WorkspaceManager
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
                    .setMessage("Current version: ${updater.currentVersion() ?: "Unknown"}\nRepository: ${old.source.owner}/${old.source.repository}\nBranch: ${old.source.branch}\n\nCheck for the latest publisher update. You can review file changes before installing.\n\nTo restore an earlier version, open Edit → Version history.")
                    .setPositiveButton("Check GitHub") { _, _ -> check(old.source) }.setNegativeButton("Cancel", null)
                builder.show()
            } else {
                val suggested = runCatching { UpdatePackage.parse(File(record.workspaceDir, "manifest.json").readText()).source }.getOrNull()
                val repo = TextInputEditText(activity).apply { setSingleLine(); setText(suggested?.let { "${it.owner}/${it.repository}" } ?: "") }
                val branch = TextInputEditText(activity).apply { setSingleLine(); setText(suggested?.branch ?: "main") }
                val padding = (24 * activity.resources.displayMetrics.density).toInt()
                val form = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding / 2, padding, padding / 2)
                    addView(TextInputLayout(activity).apply { hint = "Repository (owner/repository)"; addView(repo) })
                    addView(TextInputLayout(activity).apply { hint = "Branch"; addView(branch) })
                }
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
                val (preview, reviewed, unchanged) = withContext(Dispatchers.IO) {
                    synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                        Triple(updater.preview(value.pkg, value.directory), updater.reviewedFiles(value.pkg), updater.publisherUnchanged(value.pkg, value.directory))
                    }
                }
                val subjects = runCatching { withContext(Dispatchers.IO) { GitHubUpdateDownloader(activity.cacheDir).recentSubjects(source, updater.installed()?.commit) } }.getOrDefault(emptyList())
                progress.dismiss()
                if (unchanged) {
                    MaterialAlertDialogBuilder(activity).setTitle("App is up to date")
                        .setMessage("Version ${value.pkg.version}\n${source.display}\n\nThere are no publisher changes to install. Your local edits and saved data are kept.")
                        .setPositiveButton("Done", null)
                        .setOnDismissListener { value.directory.deleteRecursively() }.show()
                    return@launch
                }
                val text = buildString {
                    append("Version: ${value.pkg.version}\n${source.display}\n\n${preview.added.size} added · ${preview.changed.size} changed · ${preview.removed.size} removed\n")
                    if (subjects.isNotEmpty()) append("\nLatest commits:\n${subjects.joinToString("\n") { "• $it" }}\n")
                    append("\nUser records and unowned files are preserved.\n")
                    if (preview.edited.isNotEmpty()) append("\n${preview.edited.size} locally edited or missing code files will be replaced by publisher files.\n")
                    append("\nThe current and updated versions will be saved in Edit → Version history. A recovery backup will also be created.")
                }
                MaterialAlertDialogBuilder(activity).setTitle("Review app update").setMessage(text)
                    .setPositiveButton(if (preview.adoption) "Link and update" else "Update") { _, _ ->
                        applyUpdate("GitHub update ${value.pkg.version} · ${value.commit.take(12)}") { updater.install(value.pkg, value.directory, value.commit, hostVersion, reviewed) }
                        value.directory.deleteRecursively()
                    }.setNegativeButton("Cancel", null)
                    .setOnDismissListener { value.directory.deleteRecursively() }.show()
            } catch (e: Exception) { progress.dismiss(); download?.directory?.deleteRecursively(); error(e) }
        }
    }

    private fun applyUpdate(label: String, operation: () -> Unit) {
        try {
            require(!ai.hasRequest(record.id)) { "Finish and save or cancel the AI request before updating." }
            // Keep the UI thread occupied during the short local transaction so editors cannot race it.
            SubappSessionRegistry.pause(record.id)
            try {
                synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                    val workspace = WorkspaceManager(activity, workspaceDir = record.workspaceDir)
                    val history = WorkspaceHistoryManager(activity, appDirectory = record.directory)
                    history.ensureInitialVersion(workspace)
                    check(history.saveSnapshot(workspace, "Before $label") != null) { "Could not save version history; update cancelled" }
                    operation()
                    check(history.saveSnapshot(workspace, label) != null) { "App updated, but version history could not be saved" }
                }
            } finally { SubappSessionRegistry.resume(record.id) }
            Toast.makeText(activity, "App code updated; user files preserved", Toast.LENGTH_LONG).show()
            finished()
        } catch (e: Exception) { error(e) }
    }
}
