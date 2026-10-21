package com.wynh.appapp.updates

import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.LinearLayout
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
class UpdateController(private val activity: AppCompatActivity, private val record: SubappRecord) {
    private val updater = WorkspaceUpdater(record.directory)
    private val ai get() = (activity.application as AppAiApplication).ai
    private val hostCompatibilityLevel = com.wynh.appapp.BuildConfig.HOST_COMPATIBILITY_LEVEL.toLong()
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
                download = withContext(Dispatchers.IO) { GitHubUpdateDownloader(activity.cacheDir).download(source, hostCompatibilityLevel) }
                review(download, source) {
                    runCatching { withContext(Dispatchers.IO) { GitHubUpdateDownloader(activity.cacheDir).recentSubjects(source, updater.installed()?.commit) } }
                        .getOrDefault(emptyList()).also { progress.dismiss() }
                }
            } catch (e: Exception) { progress.dismiss(); download?.directory?.deleteRecursively(); error(e) }
        }
    }

    /**
     * Shows the review of a staged package; [check] calls it after downloading. The dialog owns
     * [value]'s directory and deletes it unless the update is applied. [subjects] supplies the
     * optional recent commit subjects.
     */
    internal suspend fun review(value: GitHubUpdateDownloader.Download, source: GitHubSource, subjects: suspend () -> List<String> = { emptyList() }) {
        val (preview, reviewed, unchanged) = withContext(Dispatchers.IO) {
            synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                Triple(updater.preview(value.pkg, value.directory, acceptMismatch = true), updater.reviewedFiles(value.pkg), updater.publisherUnchanged(value.pkg, value.directory))
            }
        }
        val mismatch = updater.identityMismatch(value.pkg)
        val latest = subjects()
        if (unchanged) {
            MaterialAlertDialogBuilder(activity).setTitle("App is up to date")
                .setMessage("Version ${value.pkg.version}\n${source.display}\n\nThere are no publisher changes to install. Your local edits and saved data are kept.")
                .setPositiveButton("Done", null)
                .setOnDismissListener { value.directory.deleteRecursively() }.show()
            return
        }
        val granted = com.wynh.appapp.grants.SubappGrants.store(activity).granted(record.id)
        val text = buildString {
            if (mismatch != null) append(UpdateReviewCopy.identityWarning(mismatch, granted)).append("\n\n")
            append("Version: ${value.pkg.version}\n${source.display}\n")
            if (latest.isNotEmpty()) append("\nLatest commits:\n${latest.joinToString("\n") { "• $it" }}\n")
            append("\nUser records and unowned files are preserved.\n")
            if (preview.edited.isNotEmpty()) append("\n${preview.edited.size} locally edited or missing code files will be replaced by publisher files.\n")
            append("\nThe current and updated versions will be saved in Edit → Version history. A recovery backup will also be created.")
            if (mismatch == null) append("\n\n").append(UpdateReviewCopy.permissionSummary(granted))
        }
        var accepted = false
        showReviewDialog(text, if (mismatch != null) "Update anyway" else if (preview.adoption) "Link and update" else "Update",
            onConfirm = {
                accepted = true
                applyUpdate("GitHub update ${value.pkg.version} · ${value.commit.take(12)}", value.directory) { updater.install(value.pkg, value.directory, value.commit, hostCompatibilityLevel, reviewed, acceptMismatch = mismatch != null) }
            },
            onDismissed = { if (!accepted) value.directory.deleteRecursively() })
    }

    /**
     * The review has a long message and two actions, so it uses its own layout: the message scrolls
     * independently and both full-width buttons stay visible at narrow widths and large text sizes,
     * where the platform dialog button bar can clip its last button.
     */
    private fun showReviewDialog(message: String, confirmLabel: String, onConfirm: () -> Unit, onDismissed: () -> Unit) {
        val dialog = android.app.Dialog(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(com.wynh.appapp.R.layout.dialog_update_review)
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_title).text = "Review app update"
        dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_message).text = message
        dialog.findViewById<android.widget.Button>(com.wynh.appapp.R.id.update_review_confirm).apply {
            text = confirmLabel
            setOnClickListener { onConfirm(); dialog.dismiss() }
        }
        dialog.findViewById<android.view.View>(com.wynh.appapp.R.id.update_review_cancel).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onDismissed() }
        dialog.show()
    }

    private fun applyUpdate(label: String, staged: File, operation: () -> Unit) {
        if (ai.hasRequest(record.id)) { staged.deleteRecursively(); error(IllegalStateException("Finish and save or cancel the AI request before updating.")); return }
        if (com.wynh.appapp.workspace.WorkspaceOperations.isBusy(record.workspaceDir)) {
            staged.deleteRecursively(); error(IllegalStateException("A workspace operation is already in progress.")); return
        }
        val controller = androidx.lifecycle.ViewModelProvider(activity)[com.wynh.appapp.workspace.WorkspaceActionController::class.java]
        // The controller deletes the staged download whether the operation ran, failed or was refused;
        // the screen that shows the result is whichever one is started when it finishes.
        controller.execute(record, label, completion = com.wynh.appapp.workspace.WorkspaceActionController.Completion(com.wynh.appapp.workspace.WorkspaceActionController.Kind.UPDATED),
            cleanup = { staged.deleteRecursively() }) { workspace, history ->
            history.ensureInitialVersion(workspace)
            check(history.saveSnapshot(workspace, "Before $label") != null) { "Could not save version history; update cancelled" }
            operation()
            check(history.saveSnapshot(workspace, label) != null) { "App updated, but version history could not be saved" }
        }
    }
}
