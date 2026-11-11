package com.wynh.appapp.saveas

import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wynh.appapp.channel.ChannelCall
import com.wynh.appapp.channel.ChannelException
import com.wynh.appapp.channel.ChannelProcessor
import com.wynh.appapp.channel.DocumentGuard
import com.wynh.appapp.share.ShareIntake
import com.wynh.appapp.share.ShareMessages
import com.wynh.appapp.share.ShareSessionState
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.WorkspaceFiles
import com.wynh.appapp.workspace.WorkspaceOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Origin-limited, main-frame-only `AppSave` channel. The reply is `{saved:true}` only after the
 * destination write and close succeeded, `{saved:false, cancelled:true}` when the picker was
 * dismissed, and never reaches a page that was replaced meanwhile.
 */
class SaveAsSession(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val subappId: String,
    private val workspace: File,
    private val launcher: SaveAsLauncher
) {
    private val origin = "https://$subappId.app.local"
    private val state = ShareSessionState(origin).apply { foreground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
    private val guard = DocumentGuard(workspace)
    private val app = activity.applicationContext
    private val staging = SaveAsStagingRoot.of(app)
    private fun refusal() = state.refusal(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), WorkspaceOperations.isBusy(workspace))

    private val processor = ChannelProcessor(
        CoroutineScope(SupervisorJob() + Dispatchers.Main), Dispatchers.Default, state,
        ShareIntake(maxPending = 2, maxChars = ShareMessages.MAX_MESSAGE.toLong()), ShareMessages.MAX_MESSAGE, ::refusal, guard::version
    ) { call -> handle(call) }
    private var installed = false

    private suspend fun handle(call: ChannelCall): Any? {
        if (call.action == "availability") return mapOf("available" to true, "file" to true)
        // Validation of a possibly large request stays off the UI thread.
        val request = withContext(Dispatchers.Default) { SaveAsPolicy.parse(call.action, call.options) }
        val owner = Any()
        if (!launcher.acquire(owner)) throw ChannelException("busy", "A save dialog is already open.")
        val requestId = staging.newId()
        var launched = false
        var waiter: kotlinx.coroutines.CompletableDeferred<SaveOutcome>? = null
        try {
            val (name, mime) = when (request) {
                is SaveAsPolicy.Request.Text -> request.name to request.mimeType
                is SaveAsPolicy.Request.File -> request.name to request.mimeType
            }
            try {
                withContext(Dispatchers.IO) {
                    staging.stage(subappId, requestId, exists = { SubappRepository(app).resolve(subappId) != null }) { target ->
                        when (request) {
                            is SaveAsPolicy.Request.Text -> {
                                val bytes = SaveAsPolicy.bytes(request.content)
                                java.io.FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
                            }
                            // Validation, the request's own page and generation, and the read share one workspace lock.
                            is SaveAsPolicy.Request.File -> guard.underLock(call.version, { state.current(call.page) }) {
                                SaveAsSource.snapshot(WorkspaceFiles(workspace), request.path, target)
                            }
                        }
                    }
                }
            } catch (e: SaveAsSource.Refusal) { throw ChannelException(e.code, "The workspace file cannot be saved.")
            } catch (e: StagingRefused) { throw ChannelException(if (e.code == "gone") "failed" else "busy", "The save could not be prepared.")
            } catch (_: IllegalStateException) { throw ChannelException("failed", "The save could not be prepared.") }
            kotlin.coroutines.coroutineContext.ensureActive()
            // The document may have changed or left the foreground while the snapshot was taken.
            if (!state.current(call.page) || refusal() != null || guard.stale(call.version)) throw ChannelException("busy", "The page changed during preparation.")
            waiter = SaveAsLedger.expect(requestId)
            try { launcher.launch(owner, subappId, requestId, name, mime) }
            catch (_: Exception) { throw ChannelException("unavailable", "No document picker is available.") }
            launched = true
            return when (val outcome = waiter.await()) {
                is SaveOutcome.Saved -> mapOf("saved" to true, "name" to outcome.name)
                SaveOutcome.Cancelled -> mapOf("saved" to false, "cancelled" to true)
                SaveOutcome.Failed -> throw ChannelException("write_failed", "The file could not be written.")
            }
        } finally {
            launcher.release(owner)
            waiter?.let { SaveAsLedger.forget(requestId) }
            // After launch the writer owns the staged bytes; before it, nobody does.
            if (!launched) withContext(NonCancellable + Dispatchers.IO) { staging.discard(subappId, requestId) }
        }
    }

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, "AppSave", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (!state.accepts(source.toString(), mainFrame)) return@addWebMessageListener
            processor.receive(message.data ?: return@addWebMessageListener) { reply.postMessage(it) }
        }
        installed = true
    }
    fun pause() { state.foreground = false }
    fun resume() { state.foreground = true }
    fun pageChanged() { state.pageChanged(); guard.pageStarted() }
    fun close() {
        processor.close()
        if (installed && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web, "AppSave")
        installed = false
    }
}
