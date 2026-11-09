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
/** [downloader] reaches GitHub; tests replace it. */
class UpdateController(
    private val activity: AppCompatActivity, private val record: SubappRecord,
    private val downloader: GitHubUpdateDownloader = GitHubUpdateDownloader(activity.cacheDir),
) {
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
            val names = runCatching { withContext(Dispatchers.IO) { downloader.branches(target.owner, target.repository) } }
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
     * Checks [source] behind a cancellable progress dialog, then shows the review (see [reviewCommit]).
     * Cancel stops waiting at once; a download still finishing in the background is deleted when it returns.
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
            try {
                val latest = withContext(Dispatchers.IO) { downloader.latestCommit(source) }
                // Nothing to read when the branch still points at the installed package, or at
                // a commit a full check already found identical to it.
                val installed = updater.installed()
                if (installed != null && installed.source == source && updater.currentVersion() == installed.version &&
                    (latest == installed.commit || latest == availability.knownUnchanged(record.id, source))) {
                    close()
                    showUpToDate(installed.version, source) {}
                    return@launch
                }
                reviewCommit(source, latest) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); close() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                close(); throw e
            } catch (e: Exception) { close(); checkFailed(source, e) }
        }
    }

    /**
     * Reviews [commit] of [source]. A linked app is reviewed from the new manifest and GitHub's
     * comparison of the installed and new commits, without downloading the package; the files
     * download only after the user confirms. When the comparison cannot describe the update (first
     * link, another repository, an older or diverged commit, a failed or truncated comparison), the
     * package is downloaded and compared file by file instead. [beforeShowing] runs just before a
     * dialog (or [guided]'s skip) follows. A download is deleted if this fails before the review shows.
     */
    internal suspend fun reviewCommit(source: GitHubSource, commit: String, guided: GuidedReview? = null, beforeShowing: suspend () -> Unit = {}) {
        val installed = updater.installed()
        val pkg = withContext(Dispatchers.IO) { downloader.manifest(source, commit, hostCompatibilityLevel) }
        if (guided != null && (installed?.source != source || !VersionUpdateChecker.newer(pkg.version, updater.currentVersion()))) {
            beforeShowing(); guided.onSkip(); return
        }
        val sameRepository = installed != null && installed.source.owner == source.owner && installed.source.repository == source.repository
        val comparison = if (sameRepository) runCatching { withContext(Dispatchers.IO) { downloader.compare(source, installed!!.commit, commit) } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull() else null
        val note = if (installed == null) null else UpdateReviewCopy.historyNote(comparison, otherRepository = !sameRepository)
        val planned = comparison?.let { compared ->
            withContext(Dispatchers.IO) {
                synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                    updater.plan(pkg, compared, source)?.let { it to updater.reviewedFiles(pkg) }
                }
            }
        }
        if (planned != null) {
            val (plan, reviewed) = planned
            beforeShowing()
            if (plan.unchanged) {
                availability.recordUnchanged(record.id, source, commit)
                showUpToDate(pkg.version, source) {}
                return
            }
            showReview(Pending(pkg, commit, source, plan.preview, reviewed, staged = null, reusable = plan.reusable, comparison = comparison, note = note, guided = guided))
            return
        }
        var download: GitHubUpdateDownloader.Download? = null
        try {
            withContext(Dispatchers.IO) { downloader.download(source, hostCompatibilityLevel, commit).also { download = it } }
            review(download!!, source, guided, comparison, note, beforeShowing)
        } catch (e: Throwable) { download?.directory?.deleteRecursively(); throw e }
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

    internal data class GuidedReview(
        val position: Int, val total: Int, val appName: String,
        val onSkip: () -> Unit, val onStop: () -> Unit,
        val onInstalling: () -> Unit, val onFailure: (Exception) -> Unit,
        val onShown: (android.app.Dialog) -> Unit,
    )

    /** Everything a review shows and an accepted update needs. [staged] is null until the files are downloaded. */
    private class Pending(
        val pkg: UpdatePackage, val commit: String, val source: GitHubSource, val preview: UpdatePreview,
        val reviewed: Map<String, String?>, val staged: File?, val reusable: Map<String, String> = emptyMap(),
        val comparison: Comparison? = null, val note: String? = null, val guided: GuidedReview? = null,
    )

    /**
     * Shows the review of an already downloaded package, comparing it with the workspace file by
     * file; [reviewCommit] uses it when a comparison cannot describe the update. The dialog owns
     * [value]'s directory and deletes it unless the update is applied. [beforeShowing] runs just
     * before a dialog (or [guided]'s skip) follows.
     */
    internal suspend fun review(value: GitHubUpdateDownloader.Download, source: GitHubSource, guided: GuidedReview? = null,
                                comparison: Comparison? = null, note: String? = null, beforeShowing: suspend () -> Unit = {}) {
        val (preview, reviewed, unchanged) = withContext(Dispatchers.IO) {
            synchronized(WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                Triple(updater.preview(value.pkg, value.directory, acceptMismatch = true, source = source), updater.reviewedFiles(value.pkg), updater.publisherUnchanged(value.pkg, value.directory, source))
            }
        }
        beforeShowing()
        if (guided != null && (updater.installed()?.source != source || !VersionUpdateChecker.newer(value.pkg.version, updater.currentVersion()))) {
            value.directory.deleteRecursively()
            guided.onSkip()
            return
        }
        if (unchanged) {
            availability.recordUnchanged(record.id, source, value.commit)
            showUpToDate(value.pkg.version, source) { value.directory.deleteRecursively() }
            return
        }
        showReview(Pending(value.pkg, value.commit, source, preview, reviewed, staged = value.directory, comparison = comparison, note = note, guided = guided))
    }

    private fun showReview(update: Pending) {
        val preview = update.preview
        val guided = update.guided
        val mismatch = updater.identityMismatch(update.pkg, update.source)
        val installedVersion = updater.currentVersion()
        // Older versions stay installable, for example to switch back from a dev branch, but say so.
        val older = !preview.adoption && (UpdateReviewCopy.isOlder(installedVersion, update.pkg.version) || update.comparison?.status == "behind")
        val granted = com.wynh.appapp.grants.SubappGrants.store(activity).granted(record.id)
        val latest = UpdateReviewCopy.whatsNew(update.comparison)
        // What changes comes first; the safety details follow in smaller, muted text.
        val text = android.text.SpannableStringBuilder().apply {
            fun bold(value: String) { val start = length; append(value); setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0) }
            if (mismatch != null) append(UpdateReviewCopy.identityWarning(mismatch, granted)).append("\n\n")
            // App² offers any change to managed files, so say whether the version goes up or stays.
            bold(UpdateReviewCopy.versionLine(installedVersion.takeUnless { preview.adoption }, update.pkg.version))
            // A warning above already names the new source.
            append(if (mismatch == null) "\n${update.source.display}\n" else "\n")
            if (latest.isNotEmpty()) { append("\n"); bold("What's new"); append("\n${latest.joinToString("\n")}\n") }
            update.note?.let { append("\n$it\n") }
            if (preview.edited.isNotEmpty()) append("\n⚠ ${if (preview.edited.size == 1) "1 code file you edited or removed will be replaced by the publisher's version." else "${preview.edited.size} code files you edited or removed will be replaced by the publisher's versions."}\n")
        }.trimEnd()
        // The fine print follows the changed files, so expanding the list opens right below What's new.
        val footer = "A recovery backup will be saved in Edit → Version history." +
            if (mismatch == null) "\n\n" + UpdateReviewCopy.permissionSummary(granted) else ""
        var accepted = false
        var skipped = false
        val confirm = if (older) "Install older version" else if (mismatch != null) "Update anyway" else if (preview.adoption) "Link and update" else "Update"
        val reviewDialog = showUpdateDialog(if (guided == null) "Review app update" else "${guided.appName} · ${guided.position} of ${guided.total}", text, content = changedFilesView(preview), footer = footer,
            primary = Action(confirm) { dialog ->
                accepted = true
                guided?.onInstalling?.invoke()
                applyUpdate(update, acceptMismatch = mismatch != null, onRejected = { if (guided != null) guided.onFailure(it) else error(it) })
                dialog.dismiss()
            },
            // Only an app that already follows a source can switch it from here.
            secondary = if (guided != null) Action("Skip") { skipped = true; it.dismiss(); guided.onSkip() }
                else if (preview.adoption) null else Action("Change source") { it.dismiss(); changeSource(update.source) },
            dismissLabel = if (guided != null) "Stop reviewing" else activity.getString(android.R.string.cancel),
            onDismissed = {
                if (!accepted) update.staged?.deleteRecursively()
                if (guided != null && !accepted && !skipped) guided.onStop()
            })
        guided?.onShown?.invoke(reviewDialog)
    }

    /**
     * "Show changed files (N)" that expands to the added (+), changed (~) and removed (−) paths, with
     * coloured markers, and scrolls them into view; null when none.
     */
    private fun changedFilesView(preview: UpdatePreview): android.view.View? {
        fun color(id: Int) = androidx.core.content.ContextCompat.getColor(activity, id)
        val entries = preview.added.map { Triple("+", it, com.wynh.appapp.R.color.accent_green) } +
            preview.changed.map { Triple("~", it, com.wynh.appapp.R.color.accent_blue) } +
            preview.removed.map { Triple("−", it, com.wynh.appapp.R.color.accent_red) }
        if (entries.isEmpty()) return null
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val list = android.widget.TextView(activity).apply {
            text = android.text.SpannableStringBuilder().apply {
                entries.sortedBy { it.second }.forEachIndexed { index, (mark, path, markColor) ->
                    if (index > 0) append("\n")
                    val start = length
                    append(mark)
                    setSpan(android.text.style.ForegroundColorSpan(color(markColor)), start, length, 0)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                    append("  ").append(path)
                }
            }
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(color(com.wynh.appapp.R.color.on_surface))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(com.wynh.appapp.R.color.surface_variant)); cornerRadius = dp(12).toFloat()
            }
            visibility = android.view.View.GONE
        }
        val label = { open: Boolean -> "${if (open) "Hide" else "Show"} changed files (${entries.size})" }
        val toggle = android.widget.TextView(activity).apply {
            text = label(false)
            textSize = 14f
            minHeight = dp(48)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            val ripple = android.util.TypedValue().also { activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            setBackgroundResource(ripple.resourceId)
            setTextColor(color(com.wynh.appapp.R.color.primary))
            setPadding(dp(20), 0, dp(20), 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(20), 0, dp(20), dp(4)) })
        }
        toggle.setOnClickListener {
            val open = list.visibility != android.view.View.VISIBLE
            list.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            toggle.text = label(open)
            // Bring the opened list into view: as much of it as fits, starting at the toggle.
            if (open) list.post {
                var parent = container.parent
                while (parent != null && parent !is androidx.core.widget.NestedScrollView) parent = parent.parent
                val scroll = parent as? androidx.core.widget.NestedScrollView ?: return@post
                val rect = android.graphics.Rect().also { container.getDrawingRect(it) }
                scroll.offsetDescendantRectToMyCoords(container, rect)
                val bottomAligned = rect.bottom - scroll.height
                scroll.smoothScrollTo(0, minOf(rect.top, maxOf(scroll.scrollY, bottomAligned)))
            }
        }
        return container
    }

    internal class Action(val label: String, val run: (android.app.Dialog) -> Unit)

    /**
     * Every step of the update flow uses this layout so the buttons read the same way: a filled main
     * action ([primary] null hides it), an optional tonal alternative and a text dismiss ([dismissLabel] null hides it). The
     * message scrolls independently and the full-width buttons stay visible at narrow widths and
     * large text sizes, where the platform dialog button bar can clip its last button.
     */
    internal fun showUpdateDialog(title: String, message: CharSequence, primary: Action?, secondary: Action? = null,
                                 dismissLabel: String? = activity.getString(android.R.string.cancel),
                                 content: android.view.View? = null, footer: CharSequence? = null, onDismissed: () -> Unit = {}): android.app.Dialog {
        val dialog = android.app.Dialog(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(com.wynh.appapp.R.layout.dialog_update_review)
        dialog.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_title).text = title
        dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_message).text = message
        if (content != null) {
            dialog.findViewById<android.widget.FrameLayout>(com.wynh.appapp.R.id.update_review_content).apply { addView(content); visibility = android.view.View.VISIBLE }
            // The content continues the message, so close the gap between them.
            dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_message).apply {
                setPadding(paddingLeft, paddingTop, paddingRight, (4 * activity.resources.displayMetrics.density).toInt())
            }
        }
        if (footer != null) dialog.findViewById<android.widget.TextView>(com.wynh.appapp.R.id.update_review_footer).apply { text = footer; visibility = android.view.View.VISIBLE }
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

    /**
     * Installs [update] as a workspace operation, which survives rotation. A review planned from a
     * comparison downloads the reviewed commit first, while the app keeps running, with a progress
     * count; files the publisher did not change are copied from the workspace when they still match.
     */
    private fun applyUpdate(update: Pending, acceptMismatch: Boolean, onRejected: (Exception) -> Unit = ::error) {
        val name = runCatching { com.google.gson.JsonParser.parseString(File(record.workspaceDir, "manifest.json").readText()).asJsonObject.get("name").asString }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val labels = UpdateReviewCopy.updateLabels(name, update.pkg.version)
        fun reject(message: String) { update.staged?.deleteRecursively(); onRejected(IllegalStateException(message)) }
        if (ai.hasRequest(record.id)) { reject("Finish and save or cancel the AI request before updating."); return }
        if (com.wynh.appapp.workspace.WorkspaceOperations.isBusy(record.workspaceDir)) { reject("A workspace operation is already in progress."); return }
        val controller = androidx.lifecycle.ViewModelProvider(activity)[com.wynh.appapp.workspace.WorkspaceActionController::class.java]
        if (controller.state.value.busy) { reject("A workspace operation is already in progress."); return }
        // The operation may outlive this screen, so capture plain values rather than the controller.
        val downloader = downloader
        val updater = updater
        val level = hostCompatibilityLevel
        val guided = update.guided != null
        val downloaded = java.util.concurrent.atomic.AtomicReference<File?>(update.staged)
        val prepare: (() -> Unit)? = if (update.staged != null) null else {
            {
                val result = downloader.download(update.source, level, update.commit,
                    reuse = { path, target -> update.reusable[path]?.let { updater.copyIfUnchanged(path, it, target) } ?: false },
                    progress = { done, total -> controller.report(UpdateReviewCopy.downloadProgress, done.toFloat() / total) })
                downloaded.set(result.directory)
                // The package must still be the one reviewed; install() checks the staged manifest again.
                check(result.pkg == update.pkg) { "The update changed since review; check again" }
                controller.report(UpdateReviewCopy.installProgress)
            }
        }
        // The controller deletes the staged download whether the operation ran, failed or was refused;
        // the screen that shows the result is whichever one is started when it finishes.
        controller.execute(record, labels.progress, completion = com.wynh.appapp.workspace.WorkspaceActionController.Completion(com.wynh.appapp.workspace.WorkspaceActionController.Kind.UPDATED, subject = record.id),
            cleanup = { downloaded.get()?.deleteRecursively() }, prepare = prepare) { workspace, history ->
            val files = checkNotNull(downloaded.get()) { "Update files were not downloaded" }
            if (guided) {
                check(updater.installed()?.source == update.source && VersionUpdateChecker.newer(update.pkg.version, updater.currentVersion())) { "This update no longer matches the installed app" }
            }
            history.ensureInitialVersion(workspace)
            check(history.saveSnapshot(workspace, labels.before) != null) { "Could not save version history; update cancelled" }
            updater.install(update.pkg, files, update.commit, level, update.reviewed, acceptMismatch = acceptMismatch, source = update.source)
            check(history.saveSnapshot(workspace, labels.after) != null) { "App updated, but version history could not be saved" }
        }
    }
}
