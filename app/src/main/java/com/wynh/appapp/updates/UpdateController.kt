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
import kotlinx.coroutines.ensureActive
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
            // A linked app checks its source right away; the result dialogs offer Change source.
            val old = updater.installed()
            if (old != null) check(old.source)
            else {
                val suggested = runCatching { UpdatePackage.parse(File(record.workspaceDir, "manifest.json").readText()).source }.getOrNull()
                chooseSource("Link this app to GitHub", "Choose the publisher repository for this app. No files change until you review and confirm the update.", suggested)
            }
        } catch (e: Exception) { error(e) }
    }

    private fun changeSource(current: GitHubSource) = chooseSource("Change update source",
        "Choose the repository and branch this app follows, for example a dev branch. Nothing changes until you review and confirm an update from it.", current)

    /** Asks for a repository and branch, then checks that source. */
    private fun chooseSource(title: String, message: String, initial: GitHubSource?) {
        val repo = TextInputEditText(activity).apply { setSingleLine(); setText(initial?.let { "${it.owner}/${it.repository}" } ?: "") }
        val branch = TextInputEditText(activity).apply { setSingleLine(); setText(initial?.branch ?: "main") }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding * 5 / 6, 0, padding * 5 / 6, padding / 2)
            addView(TextInputLayout(activity).apply { hint = "Repository (owner/repository)"; addView(repo) })
            addView(TextInputLayout(activity).apply {
                hint = "Branch"; addView(branch)
                endIconMode = TextInputLayout.END_ICON_CUSTOM
                setEndIconDrawable(com.wynh.appapp.R.drawable.ic_arrow_down)
                endIconContentDescription = "Choose a branch"
                setEndIconOnClickListener { pickBranch(repo, branch, this) }
            })
        }
        showUpdateDialog(title, message, content = form, primary = Action("Check for updates") { dialog ->
            try { val source = parseSource(repo.text.toString(), branch.text.toString()); dialog.dismiss(); check(source) }
            catch (e: Exception) { repo.error = e.message }
        })
    }

    /** Loads the repository's branches from GitHub and fills [branch] with the one the user picks. */
    private fun pickBranch(repo: TextInputEditText, branch: TextInputEditText, layout: TextInputLayout) {
        val target = try { parseSource(repo.text.toString(), "main") } catch (e: Exception) { repo.error = e.message; return }
        layout.error = null
        layout.helperText = "Loading branches…"
        activity.lifecycleScope.launch {
            val names = runCatching { withContext(Dispatchers.IO) { GitHubUpdateDownloader(activity.cacheDir).branches(target.owner, target.repository) } }
            layout.helperText = null
            if (activity.isFinishing) return@launch
            names.onFailure { layout.error = it.message ?: "Could not load branches" }.onSuccess { list ->
                if (list.isEmpty()) { layout.error = "No branches found"; return@onSuccess }
                val current = list.indexOf(branch.text.toString().trim())
                MaterialAlertDialogBuilder(activity).setTitle("Branches of ${target.owner}/${target.repository}")
                    .setSingleChoiceItems(list.toTypedArray(), current) { dialog, which -> branch.setText(list[which]); dialog.dismiss() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /**
     * Downloads [source] behind a cancellable progress dialog, then shows the review. Cancel stops
     * waiting at once; a download still finishing in the background is deleted when it returns.
     */
    private fun check(source: GitHubSource) {
        var job: kotlinx.coroutines.Job? = null
        var finished = false
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val indicator = com.google.android.material.progressindicator.LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val frame = android.widget.FrameLayout(activity).apply { setPadding(padding, 0, padding, padding / 2); addView(indicator) }
        // Cancel, Back or tapping outside all stop waiting; dismissing after the check finishes does not.
        val progress = showUpdateDialog("Checking for updates…", source.display, primary = null, content = frame,
            onDismissed = { if (!finished) job?.cancel() })
        fun close() { finished = true; progress.dismiss() }
        job = activity.lifecycleScope.launch {
            var download: GitHubUpdateDownloader.Download? = null
            try {
                val downloader = GitHubUpdateDownloader(activity.cacheDir)
                val latest = withContext(Dispatchers.IO) { downloader.latestCommit(source) }
                // Nothing to download when the branch still points at the installed package, or at
                // a commit a full check already found identical to it.
                val installed = updater.installed()
                // Only the followed source feeds the library badge; trying another one leaves it alone.
                if (installed?.source == source) availability.recordLatest(record.id, source, latest)
                if (installed != null && installed.source == source && updater.currentVersion() == installed.version &&
                    (latest == installed.commit || latest == availability.knownUnchanged(record.id, source))) {
                    close()
                    showUpToDate(installed.version, source) {}
                    return@launch
                }
                withContext(Dispatchers.IO) { downloader.download(source, hostCompatibilityLevel, latest).also { download = it } }
                review(download!!, source) {
                    val subjects = runCatching { withContext(Dispatchers.IO) { GitHubUpdateDownloader(activity.cacheDir).recentSubjects(source, updater.installed()?.commit) } }
                        .getOrDefault(emptyList())
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    close()
                    subjects
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                close(); download?.directory?.deleteRecursively(); throw e
            } catch (e: Exception) { close(); download?.directory?.deleteRecursively(); checkFailed(source, e) }
        }
    }

    private val availability get() = UpdateAvailability.of(activity)

    private fun showUpToDate(version: String, source: GitHubSource, onDismissed: () -> Unit) = showUpdateDialog("App is up to date",
        "Version $version\n${source.display}\n\nThere are no publisher changes to install. Your local edits and saved data are kept.\n\nTo restore an earlier version, open Edit → Version history.",
        primary = Action("Done") { it.dismiss() },
        secondary = Action("Change source") { it.dismiss(); changeSource(source) },
        dismissLabel = null, onDismissed = onDismissed)

    private fun checkFailed(source: GitHubSource, e: Exception) {
        if (activity.isFinishing) return
        showUpdateDialog("Couldn't check for updates", UpdateReviewCopy.checkFailure(e, source),
            primary = Action("Try again") { it.dismiss(); check(source) },
            secondary = Action("Change source") { it.dismiss(); changeSource(source) },
            dismissLabel = "Close")
    }

    /**
     * Shows the review of a staged package; [check] calls it after downloading. The dialog owns
     * [value]'s directory and deletes it unless the update is applied. [subjects] supplies the
     * optional recent commit subjects.
     */
    internal suspend fun review(value: GitHubUpdateDownloader.Download, source: GitHubSource, subjects: suspend () -> List<String> = { emptyList() }) {
        val (preview, reviewed, unchanged) = withContext(Dispatchers.IO) {
            synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                Triple(updater.preview(value.pkg, value.directory, acceptMismatch = true, source = source), updater.reviewedFiles(value.pkg), updater.publisherUnchanged(value.pkg, value.directory, source))
            }
        }
        val mismatch = updater.identityMismatch(value.pkg, source)
        val latest = subjects()
        val installedVersion = updater.currentVersion()
        if (unchanged) {
            availability.recordUnchanged(record.id, source, value.commit)
            showUpToDate(value.pkg.version, source) { value.directory.deleteRecursively() }
            return
        }
        // Older versions stay installable, for example to switch back from a dev branch, but say so.
        val older = !preview.adoption && UpdateReviewCopy.isOlder(installedVersion, value.pkg.version)
        val granted = com.wynh.appapp.grants.SubappGrants.store(activity).granted(record.id)
        // What changes comes first; the safety details follow in smaller, muted text.
        val text = android.text.SpannableStringBuilder().apply {
            fun bold(value: String) { val start = length; append(value); setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0) }
            if (mismatch != null) append(UpdateReviewCopy.identityWarning(mismatch, granted)).append("\n\n")
            // App² offers any change to managed files, so say whether the version goes up or stays.
            bold(UpdateReviewCopy.versionLine(installedVersion.takeUnless { preview.adoption }, value.pkg.version))
            append("\n${source.display}\n")
            if (latest.isNotEmpty()) { append("\n"); bold("What's new"); append("\n${latest.joinToString("\n") { "• $it" }}\n") }
            if (preview.edited.isNotEmpty()) append("\n⚠ ${if (preview.edited.size == 1) "1 code file you edited or removed will be replaced by the publisher's version." else "${preview.edited.size} code files you edited or removed will be replaced by the publisher's versions."}\n")
            val start = length
            append("\nYour records and files the publisher doesn't own are kept. The current and updated versions are saved in Edit → Version history, with a recovery backup.")
            if (mismatch == null) append("\n\n").append(UpdateReviewCopy.permissionSummary(granted))
            setSpan(android.text.style.RelativeSizeSpan(0.9f), start, length, 0)
            setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(activity, com.wynh.appapp.R.color.on_surface_muted)), start, length, 0)
        }
        var accepted = false
        val confirm = if (older) "Install older version" else if (mismatch != null) "Update anyway" else if (preview.adoption) "Link and update" else "Update"
        showUpdateDialog("Review app update", text, content = changedFilesView(preview),
            primary = Action(confirm) { dialog ->
                accepted = true
                applyUpdate("GitHub update ${value.pkg.version} · ${value.commit.take(12)}", value.directory) { updater.install(value.pkg, value.directory, value.commit, hostCompatibilityLevel, reviewed, acceptMismatch = mismatch != null, source = source) }
                dialog.dismiss()
            },
            // Only an app that already follows a source can switch it from here.
            secondary = if (preview.adoption) null else Action("Change source") { it.dismiss(); changeSource(source) },
            onDismissed = { if (!accepted) value.directory.deleteRecursively() })
    }

    /** "Show changed files (N)" that expands to the added, changed and removed paths; null when none. */
    private fun changedFilesView(preview: UpdatePreview): android.view.View? {
        val lines = preview.added.map { "+ $it" } + preview.changed.map { "~ $it" } + preview.removed.map { "− $it" }
        if (lines.isEmpty()) return null
        val density = activity.resources.displayMetrics.density
        val list = android.widget.TextView(activity).apply {
            text = lines.sorted().joinToString("\n") { it }
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(activity, com.wynh.appapp.R.color.on_surface_muted))
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), (12 * density).toInt())
            visibility = android.view.View.GONE
        }
        val label = { open: Boolean -> "${if (open) "Hide" else "Show"} changed files (${lines.size})" }
        val toggle = android.widget.TextView(activity).apply {
            text = label(false)
            textSize = 14f
            minHeight = (48 * density).toInt()
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            val ripple = android.util.TypedValue().also { activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            setBackgroundResource(ripple.resourceId)
            setTextColor(androidx.core.content.ContextCompat.getColor(activity, com.wynh.appapp.R.color.primary))
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            setOnClickListener {
                val open = list.visibility != android.view.View.VISIBLE
                list.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = label(open)
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(list)
        }
    }

    private class Action(val label: String, val run: (android.app.Dialog) -> Unit)

    /**
     * Every step of the update flow uses this layout so the buttons read the same way: a filled main
     * action ([primary] null hides it), an optional tonal alternative and a text dismiss ([dismissLabel] null hides it). The
     * message scrolls independently and the full-width buttons stay visible at narrow widths and
     * large text sizes, where the platform dialog button bar can clip its last button.
     */
    private fun showUpdateDialog(title: String, message: CharSequence, primary: Action?, secondary: Action? = null,
                                 dismissLabel: String? = activity.getString(android.R.string.cancel),
                                 content: android.view.View? = null, onDismissed: () -> Unit = {}): android.app.Dialog {
        val dialog = android.app.Dialog(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(com.wynh.appapp.R.layout.dialog_update_review)
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_title).text = title
        dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_message).text = message
        if (content != null) dialog.findViewById<android.widget.FrameLayout>(com.wynh.appapp.R.id.update_review_content).apply { addView(content); visibility = android.view.View.VISIBLE }
        dialog.findViewById<android.widget.Button>(com.wynh.appapp.R.id.update_review_confirm).apply {
            if (primary == null) visibility = android.view.View.GONE
            else { text = primary.label; setOnClickListener { primary.run(dialog) } }
        }
        dialog.findViewById<android.widget.Button>(com.wynh.appapp.R.id.update_review_secondary).apply {
            if (secondary != null) { text = secondary.label; visibility = android.view.View.VISIBLE; setOnClickListener { secondary.run(dialog) } }
        }
        dialog.findViewById<android.widget.Button>(com.wynh.appapp.R.id.update_review_cancel).apply {
            if (dismissLabel == null) visibility = android.view.View.GONE
            else { text = dismissLabel; setOnClickListener { dialog.dismiss() } }
        }
        dialog.setOnDismissListener { onDismissed() }
        dialog.show()
        return dialog
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
