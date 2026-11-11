package com.wynh.appapp.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wynh.appapp.workspace.WorkspaceOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** An origin-limited, main-frame-only channel; success means the chooser was launched. */
class ShareSession(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val subappId: String,
    private val workspace: File,
    private val launcher: ShareLauncher
) {
    private val origin = "https://$subappId.app.local"
    private val state = ShareSessionState(origin).apply {
        foreground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    private val app = activity.applicationContext
    // Parsing runs on Default and cache preparation on I/O; launch and replies stay on the main thread.
    private val processor = ShareProcessor(CoroutineScope(SupervisorJob() + Dispatchers.Main), Dispatchers.Default, Dispatchers.IO, state,
        object : ShareProcessor.Effects {
            override fun refusal() = state.refusal(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), WorkspaceOperations.isBusy(workspace))
            override fun workspaceVersion() = WorkspaceOperations.version(workspace)
            override fun acquire(owner: Any) = launcher.acquire(owner)
            override fun release(owner: Any) = launcher.release(owner)
            override fun sweep() = ShareCache.sweep(app.cacheDir)
            override fun write(request: SharePolicy.Request.Document) = ShareCache.write(app.cacheDir, subappId, request)
            override fun discard(file: File) = ShareCache.discard(file)
            override fun launch(owner: Any, request: SharePolicy.Request, file: File?) {
                val send = when (request) {
                    is SharePolicy.Request.Text -> Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, request.text)
                        request.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    }
                    is SharePolicy.Request.Document -> {
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", checkNotNull(file))
                        Intent(Intent.ACTION_SEND).apply {
                            type = request.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(request.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    SharePolicy.Request.Availability -> error("Unexpected availability request")
                }
                val title = (request as? SharePolicy.Request.Text)?.title
                try { launcher.launch(owner, Intent.createChooser(send, title)) }
                catch (_: ActivityNotFoundException) { throw ShareException("unavailable", "The Android Sharesheet is unavailable.") }
            }
        })
    private var installed = false

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(web, "AppShare", setOf(origin)) { _, message, source, mainFrame, reply ->
            if (!state.accepts(source.toString(), mainFrame)) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            processor.receive(raw) { reply.postMessage(it) }
        }
        installed = true
    }
    fun pause() { state.foreground = false }
    fun resume() { state.foreground = true }
    fun pageChanged() { state.pageChanged() }
    fun close() {
        processor.close()
        if (installed && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(web, "AppShare")
        }
        installed = false
    }
}
