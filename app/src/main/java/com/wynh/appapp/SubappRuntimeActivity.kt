package com.wynh.appapp

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wynh.appapp.bridge.NativeStorageBridge
import com.wynh.appapp.runtime.SubappWebViews
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.WorkspaceManager

/** Standalone document task bound to the app ID encoded in its launch URI. */
class SubappRuntimeActivity : AppCompatActivity(), UpdatableSubappSession {
    private var runtimeSession: com.wynh.appapp.runtime.RuntimeSession? = null
    private var webView: WebView? = null
    private var activeId: String? = null
    private var historyBackPending = false
    private var didInitialResume = false
    private val runtimeChooser = com.wynh.appapp.runtime.RuntimeFileChooser(this) { message ->
        webView?.post { activeId?.let { id ->
            com.wynh.appapp.workspace.WorkspaceLog.append(applicationContext, java.io.File(filesDir, "subapps/$id/workspace"), message)
        } }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleWebHistoryBack(webView)
            }
        })
        NativeStorageBridge.ensureNotificationChannel(this)
        com.wynh.appapp.runtime.WorkspaceOperationUi.bind(this)
        render(intent.data)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != Uri.parse("${com.wynh.appapp.BuildConfig.APP_LINK_SCHEME}://subapp/$activeId")) render(intent.data)
    }

    private fun render(uri: Uri?) {
        val id = uri?.lastPathSegment
        val record = id?.let { SubappRepository(this).resolve(it) }
        if (record == null) {
            runtimeSession?.close(); runtimeSession = null
            webView?.destroy(); webView = null
            setContentView(TextView(this).apply {
                text = "App no longer available"
                textSize = 20f
                gravity = android.view.Gravity.CENTER
            })
            activeId = null
            return
        }
        if (activeId == record.id && webView != null) return
        historyBackPending = false
        activeId?.let { SubappSessionRegistry.unregister(it, this) }
        runtimeSession?.close()
        webView?.destroy()
        activeId = record.id
        val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
        updateTaskAppearance(manager)
        SubappSessionRegistry.register(record.id, this)
        val root = FrameLayout(this)
        val web = SelectionAwareWebView(this)
        webView = web
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val density = resources.displayMetrics.density
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            fun css(value: Int) = String.format(java.util.Locale.ROOT, "%.2fpx", value / density)
            web.evaluateJavascript("""(function(s){s.setProperty('--app-inset-top','${css(insets.top)}');s.setProperty('--app-inset-right','${css(insets.right)}');s.setProperty('--app-inset-bottom','${css(insets.bottom)}');s.setProperty('--app-inset-left','${css(insets.left)}');})(document.documentElement.style);window.dispatchEvent(new CustomEvent('app2:insets',{detail:{imeVisible:${windowInsets.isVisible(WindowInsetsCompat.Type.ime())}}}));""", null)
            windowInsets
        }
        val log: (String) -> Unit = { com.wynh.appapp.workspace.WorkspaceLog.append(applicationContext, record.workspaceDir, it) }
        val bridge = NativeStorageBridge(
            this, record.id, record.workspaceDir,
            onRequestNotificationPermission = {
                if (android.os.Build.VERSION.SDK_INT >= 33) runOnUiThread {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onSetSelectionMenuSuppressed = { suppressed ->
                runOnUiThread { web.selectionMenuSuppressed = suppressed }
            }
        )
        runtimeSession = com.wynh.appapp.runtime.RuntimeSession(this, web, record, manager, bridge, runtimeChooser, log,
            WebSettings.LOAD_DEFAULT, ready = { view -> installWebHistoryTracking(view); ViewCompat.requestApplyInsets(root) })
        web.loadUrl(SubappWebViews.entryUrl(record.id, manager))
        NativeStorageBridge.reschedulePersistedAlarms(this, record.id)
    }

    /** Android WebView does not consistently include History API entries in canGoBack(). */
    private fun installWebHistoryTracking(view: WebView?) {
        view?.evaluateJavascript(
            """(function(){if(window.__app2HistoryTracking)return;window.__app2HistoryTracking=true;window.__app2HistoryDepth=0;var original=history.pushState;history.pushState=function(){var value=original.apply(this,arguments);window.__app2HistoryDepth++;return value;};addEventListener('popstate',function(){window.__app2HistoryDepth=Math.max(0,window.__app2HistoryDepth-1);});})();""",
            null
        )
    }

    private fun handleWebHistoryBack(view: WebView?) {
        if (view == null || historyBackPending) return
        historyBackPending = true
        view.evaluateJavascript(
            "(function(){if((window.__app2HistoryDepth||0)>0){history.back();return true;}return false;})()"
        ) { result ->
            historyBackPending = false
            if (webView !== view || isFinishing || result == "true") return@evaluateJavascript
            if (view.canGoBack()) view.goBack()
            else finish()
        }
    }

    override fun onResume() {
        runtimeSession?.resume()
        super.onResume()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(true)
        if (didInitialResume) {
            val id = activeId ?: return
            val data = intent.data
            val record = SubappRepository(this).resolve(id)
            if (data?.lastPathSegment == id && record != null) {
                updateTaskAppearance(WorkspaceManager(this, workspaceDir = record.workspaceDir))
            }
        }
        didInitialResume = true
    }

    override fun onPause() {
        runtimeSession?.pause()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(false)
        super.onPause()
    }

    private fun updateTaskAppearance(manager: WorkspaceManager) {
        val metadata = com.wynh.appapp.workspace.SubappMetadataLoader.load(manager.workspaceDir)
        val name = metadata.name.ifBlank { "App²" }
        title = name
        val taskIcon = metadata.icon
        setTaskDescription(android.app.ActivityManager.TaskDescription(name, taskIcon, 0xff263238.toInt()))
    }

    override fun pauseForUpdate() {
        runtimeSession?.suspendForOperation()
    }

    override fun resumeAfterUpdate() {
        runtimeSession?.resumeAfterOperation()
    }

    override fun onDestroy() {
        runtimeSession?.close()
        if (isFinishing) (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelTask(taskId)
        activeId?.let { SubappSessionRegistry.unregister(it, this) }
        runtimeChooser.complete(null)
        webView?.destroy(); webView = null; super.onDestroy()
    }
}
