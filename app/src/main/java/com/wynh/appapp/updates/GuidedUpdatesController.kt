package com.wynh.appapp.updates

import android.app.Dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wynh.appapp.workspace.SubappRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A review queue never installs anything until the user accepts each existing update review. */
class GuidedUpdatesController(
    private val activity: AppCompatActivity,
    private val records: List<SubappRecord>,
    private val changed: () -> Unit,
    private val finished: () -> Unit,
    private val downloader: GitHubUpdateDownloader = GitHubUpdateDownloader(activity.cacheDir),
) {
    private val indicators = SubappUpdateIndicators.of(activity)
    private var index = 0
    private var stopped = false
    private var waitingForInstall = false
    private var job: Job? = null
    private var dialog: Dialog? = null

    fun start() { next() }
    fun stop() {
        if (stopped) return
        stopped = true
        job?.cancel()
        dialog?.dismiss(); dialog = null
        finished()
    }
    fun installed(id: String) {
        if (!stopped && waitingForInstall && records.getOrNull(index)?.id == id) {
            indicators.clear(id)
            waitingForInstall = false
            changed()
            advance()
        }
    }
    fun failed(message: String): Boolean {
        if (stopped || !waitingForInstall) return false
        waitingForInstall = false
        failure(IllegalStateException(message))
        return true
    }
    private fun advance() { index++; next() }
    private fun next() {
        if (stopped || activity.isFinishing || activity.isDestroyed) return
        if (index >= records.size) { stop(); return }
        val record = records[index]
        val available = indicators.available(record)
        if (available == null) { changed(); advance(); return }
        val name = runCatching { com.google.gson.JsonParser.parseString(java.io.File(record.workspaceDir, "manifest.json").readText()).asJsonObject.get("name").asString }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Subapp"
        val controller = UpdateController(activity, record)
        var loaded = false
        dialog = controller.showUpdateDialog("Preparing update ${index + 1} of ${records.size}",
            "$name\nChecking and downloading the selected update…", primary = null, dismissLabel = "Stop reviewing",
            onDismissed = { if (!loaded) stop() })
        job = activity.lifecycleScope.launch {
            var download: GitHubUpdateDownloader.Download? = null
            try {
                withContext(Dispatchers.IO) {
                    downloader.download(available.source,
                        com.wynh.appapp.BuildConfig.HOST_COMPATIBILITY_LEVEL.toLong(), available.commit).also { download = it }
                }
                ensureActive()
                loaded = true
                dialog?.dismiss(); dialog = null
                controller.review(download!!, available.source, UpdateController.GuidedReview(
                    index + 1, records.size, name,
                    onSkip = { changed(); advance() }, onStop = ::stop,
                    onInstalling = { waitingForInstall = true; dialog = null },
                    onFailure = { waitingForInstall = false; failure(it) },
                    onShown = { dialog = it },
                ))
            } catch (e: CancellationException) {
                download?.directory?.deleteRecursively(); throw e
            } catch (e: Exception) {
                loaded = true
                dialog?.dismiss(); dialog = null
                download?.directory?.deleteRecursively()
                failure(e)
            }
        }
    }
    private fun failure(e: Exception) {
        if (stopped || activity.isFinishing || activity.isDestroyed) return
        val record = records[index]
        var continuing = false
        dialog = UpdateController(activity, record).showUpdateDialog("Couldn't update app",
            e.message ?: "Update failed", primary = UpdateController.Action("Retry") {
                continuing = true; it.dismiss(); next()
            }, secondary = UpdateController.Action("Skip") {
                continuing = true; it.dismiss(); advance()
            }, dismissLabel = "Stop reviewing", onDismissed = { if (!continuing) stop() })
    }
}
