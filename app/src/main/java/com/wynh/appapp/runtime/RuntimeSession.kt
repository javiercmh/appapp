package com.wynh.appapp.runtime

import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.R
import com.wynh.appapp.SelectionAwareWebView
import com.wynh.appapp.ai.AppAiApplication
import com.wynh.appapp.ai.AiWebMessageBridge
import com.wynh.appapp.bridge.NativeStorageBridge
import com.wynh.appapp.grants.SubappCapability
import com.wynh.appapp.grants.SubappGrants
import com.wynh.appapp.speech.SpeechSession
import com.wynh.appapp.translation.TranslationSession
import com.wynh.appapp.updates.UpdateWebMessageBridge
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.WorkspaceManager

/** Owns capability installation and lifecycle equally in preview and standalone execution. */
class RuntimeSession(
    private val activity: AppCompatActivity,
    private val web: SelectionAwareWebView,
    record: SubappRecord,
    manager: WorkspaceManager,
    val storage: NativeStorageBridge,
    private val chooser: RuntimeFileChooser,
    private val log: (String) -> Unit,
    cacheMode: Int,
    ready: (WebView) -> Unit,
    progress: (Int) -> Unit = {}
) {
    private val speech = SpeechSession(activity, web, record.id)
    private val translation = TranslationSession(activity, web, record.id)
    private var closed = false
    private var suspended = false
    private var webPaused = false
    private var reloadPending = false
    init {
        SubappWebViews.configure(web, cacheMode)
        speech.install(); translation.install()
        SubappWebViews.installStorageBridge(web, record.id, storage, log)
        (activity.application as? AppAiApplication)?.ai?.let { AiWebMessageBridge(activity, web, record.id, activity.taskId, it).install() }
        UpdateWebMessageBridge(activity, web, record).install()
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("[${message.messageLevel()}] (line ${message.lineNumber()}) ${message.message()}"); return true
            }
            override fun onProgressChanged(view: WebView?, value: Int) { progress(value) }
            override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean = chooser.open(callback, params)
        }
        web.webViewClient = object : SubappWebViewClient(activity, record.id, { manager }, log, {
            SubappGrants.isGranted(activity, record.id, SubappCapability.NETWORK)
        }) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                chooser.complete(null); speech.pageChanged(); web.selectionMenuSuppressed = false; storage.pageStarted()
                super.onPageStarted(view, url, favicon)
            }
            override fun onPageFinished(view: WebView?, url: String?) { super.onPageFinished(view, url); if (!closed) ready(web) }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                log("[WebView Error] ${error?.description ?: "Request failed"}")
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                close()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
                if (!activity.isFinishing && !activity.isDestroyed) MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.runtime_stopped_title).setMessage(R.string.runtime_stopped_message).setCancelable(false)
                    .setPositiveButton(R.string.runtime_reload) { _, _ -> activity.recreate() }.show()
                return true
            }
        }
    }
    fun pause() { if (!closed) { speech.pause(); translation.pause() } }

    /**
     * Called from the activity's onResume, before the lifecycle state reads RESUMED. It also
     * performs what a replacement finished while the activity was in the background.
     */
    fun resume() {
        if (closed || suspended) return
        if (webPaused) { web.onResume(); webPaused = false }
        speech.resume(); translation.resume()
        if (reloadPending) { reloadPending = false; web.reload() }
    }

    /** A workspace replacement is starting: stop the page, which can no longer write to the new generation. */
    fun suspendForOperation() { if (!closed) { suspended = true; pause(); web.onPause(); webPaused = true; web.visibility = View.INVISIBLE } }

    /**
     * The replacement ended: the page must reload. A foreground activity does so now; one that is
     * paused or stopped keeps its WebView paused, and speech and translation not foreground,
     * until its next onResume.
     */
    fun resumeAfterOperation() {
        if (closed) return
        suspended = false; reloadPending = true; web.visibility = View.VISIBLE
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
    }
    fun close() { if (!closed) { closed = true; chooser.complete(null); speech.close(); translation.close() } }
}
