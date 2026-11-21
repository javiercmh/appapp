package com.wynh.appapp.updates

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.wynh.appapp.BuildConfig
import com.wynh.appapp.R
import com.wynh.appapp.workspace.SubappRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compact, manual update check for both App² and all linked subapps. */
class SettingsUpdateController(
    private val activity: AppCompatActivity,
    private val dialog: Dialog,
    private val records: () -> List<SubappRecord>,
    private val changed: () -> Unit,
    private val review: (List<SubappRecord>) -> Unit,
    private val checker: VersionUpdateChecker = VersionUpdateChecker(),
) {
    private val indicators = SubappUpdateIndicators.of(activity)
    private val check = dialog.findViewById<MaterialButton>(R.id.settings_check_updates)
    private val progress = dialog.findViewById<View>(R.id.settings_update_progress)
    private val host = dialog.findViewById<TextView>(R.id.settings_host_update_status)
    private val subapps = dialog.findViewById<TextView>(R.id.settings_subapp_update_status)
    private val release = dialog.findViewById<MaterialButton>(R.id.settings_view_release)
    private val reviews = dialog.findViewById<MaterialButton>(R.id.settings_review_updates)
    private var job: Job? = null

    init { check.setOnClickListener { start() } }
    fun close() { job?.cancel() }

    fun start() {
        if (job?.isActive == true) return
        check.isEnabled = false
        check.visibility = View.GONE
        progress?.visibility = View.VISIBLE
        check.setText(R.string.updates_checking)
        host.visibility = View.VISIBLE
        host.setText(R.string.updates_host_checking)
        subapps.visibility = View.VISIBLE
        subapps.setText(R.string.updates_subapps_checking)
        release.visibility = View.GONE
        reviews.visibility = View.GONE
        job = activity.lifecycleScope.launch {
            var failed = false
            try {
                try {
                    val latest = withContext(Dispatchers.IO) { checker.hostRelease(BuildConfig.BUILD_TYPE == "dev") }
                    val newer = latest?.isNewer(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_TYPE == "dev") == true
                    host.text = if (latest == null) activity.getString(R.string.updates_no_release)
                        else activity.getString(if (newer) R.string.updates_host_new else R.string.updates_host_current, latest.version)
                    if (newer) {
                        release.visibility = View.VISIBLE
                        release.setOnClickListener {
                            try { activity.startActivity(Intent(Intent.ACTION_VIEW, latest.url.toUri())) }
                            catch (_: ActivityNotFoundException) { Toast.makeText(activity, R.string.updates_browser_missing, Toast.LENGTH_SHORT).show() }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true; host.setText(R.string.updates_host_failed) }

                val apps = withContext(Dispatchers.IO) { records() }
                val session = checker.SubappCheckSession(UpdateAvailability.of(activity))
                val found = mutableListOf<SubappRecord>()
                var failures = 0
                var skipped = 0
                for ((index, record) in apps.withIndex()) {
                    ensureActive()
                    subapps.text = activity.getString(R.string.updates_subapp_progress, index + 1, apps.size)
                    try {
                        val result = withContext(Dispatchers.IO) {
                            session.check(record.id, WorkspaceUpdater(record.directory))
                        }
                        ensureActive()
                        when (result) {
                            VersionUpdateChecker.SubappCheckResult.Unlinked -> { skipped++; indicators.clear(record.id) }
                            VersionUpdateChecker.SubappCheckResult.UpToDate -> indicators.clear(record.id)
                            is VersionUpdateChecker.SubappCheckResult.Available -> {
                                val available = result.version
                                // Revalidate locally after the request: the workspace may have changed during it.
                                val current = withContext(Dispatchers.IO) {
                                    val updater = WorkspaceUpdater(record.directory)
                                    val installed = updater.installed()
                                    installed?.source == available.source &&
                                        VersionUpdateChecker.isApplicable(available, updater.currentVersion(), installed.commit)
                                }
                                ensureActive()
                                if (current) { indicators.record(record.id, available); found.add(record) }
                                else indicators.clear(record.id)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failures++ }
                    changed()
                }
                val summary = if (found.isEmpty()) activity.getString(if (failures == 0) R.string.updates_subapps_current else R.string.updates_subapps_incomplete)
                    else activity.resources.getQuantityString(R.plurals.updates_subapps_found, found.size, found.size)
                subapps.text = buildString {
                    append(summary)
                    if (failures > 0) append("\n").append(activity.resources.getQuantityString(R.plurals.updates_failed_count, failures, failures))
                    if (skipped > 0) append("\n").append(activity.resources.getQuantityString(R.plurals.updates_unlinked_count, skipped, skipped))
                }
                failed = failed || failures > 0
                if (found.isNotEmpty()) {
                    reviews.visibility = View.VISIBLE
                    reviews.setOnClickListener { dialog.dismiss(); review(found) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; subapps.setText(R.string.updates_subapps_incomplete) }
            finally {
                progress?.visibility = View.GONE
                check.isEnabled = true
                check.visibility = View.VISIBLE
                check.setText(if (failed) R.string.updates_retry else R.string.updates_check)
            }
        }
    }
}
