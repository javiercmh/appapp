package com.wynh.appapp

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wynh.appapp.bridge.NativeStorageBridge
import com.wynh.appapp.runtime.SubappWebViewClient
import com.wynh.appapp.runtime.SubappWebViews
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.WorkspaceManager
import org.json.JSONObject

/** Standalone document task bound to the app ID encoded in its launch URI. */
class SubappRuntimeActivity : AppCompatActivity(), UpdatableSubappSession {
    private var speechSession: com.wynh.appapp.speech.SpeechSession? = null
    private var translationSession: com.wynh.appapp.translation.TranslationSession? = null
    private var webView: WebView? = null
    private var activeId: String? = null
    private var historyBackPending = false
    private var didInitialResume = false
    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        chooserCallback?.onReceiveValue(if (uri == null) null else arrayOf(uri))
        chooserCallback = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleWebHistoryBack(webView)
            }
        })
        NativeStorageBridge.ensureNotificationChannel(this)
        render(intent.data)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != Uri.parse("appapp://subapp/$activeId")) render(intent.data)
    }

    private fun render(uri: Uri?) {
        val id = uri?.lastPathSegment
        val record = id?.let { SubappRepository(this).resolve(it) }
        if (record == null) {
            speechSession?.close(); speechSession = null
            translationSession?.close(); translationSession = null
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
        speechSession?.close()
        translationSession?.close()
        webView?.destroy()
        activeId = record.id
        val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
        updateTaskAppearance(manager)
        SubappSessionRegistry.register(record.id, this)
        val root = FrameLayout(this)
        val web = SelectionAwareWebView(this)
        speechSession = com.wynh.appapp.speech.SpeechSession(this, web, record.id).also { it.install() }
        translationSession = com.wynh.appapp.translation.TranslationSession(this, web, record.id).also { it.install() }
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
        SubappWebViews.configure(web, WebSettings.LOAD_DEFAULT)
        val log: (String) -> Unit = { manager.appendToFile("app.log", "$it\n") }
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
        SubappWebViews.installStorageBridge(web, record.id, bridge, log)
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.let {
            com.wynh.appapp.ai.AiWebMessageBridge(this, web, record.id, taskId, it).install()
        }
        com.wynh.appapp.updates.UpdateWebMessageBridge(this, web, record).install()
        web.webViewClient = object : SubappWebViewClient(this, record.id, { manager }, log, {
            com.wynh.appapp.grants.SubappGrants.isGranted(this, record.id, com.wynh.appapp.grants.SubappCapability.NETWORK)
        }) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                speechSession?.pageChanged()
                web.selectionMenuSuppressed = false
                bridge.pageStarted()
                super.onPageStarted(view, url, favicon)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                installWebHistoryTracking(view)
                ViewCompat.requestApplyInsets(root)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                chooserCallback?.onReceiveValue(null)
                chooserCallback = callback
                filePicker.launch(params?.acceptTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*")
                return true
            }
        }
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
        speechSession?.resume()
        translationSession?.resume()
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
        speechSession?.pause()
        translationSession?.pause()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(false)
        super.onPause()
    }

    private fun updateTaskAppearance(manager: WorkspaceManager) {
        val manifest = try { JSONObject(manager.readFile("manifest.json")) } catch (_: Exception) { JSONObject() }
        val name = manifest.optString("name", "App²")
        title = name
        val iconPath = manifest.optJSONArray("icons")?.optJSONObject(0)?.optString("src")
        val iconFile = iconPath?.let(manager::getFile) ?: manager.getFile("icon.png")
        val taskIcon = if (iconFile.isFile) runCatching { android.graphics.BitmapFactory.decodeFile(iconFile.absolutePath) }.getOrNull() else null
        setTaskDescription(android.app.ActivityManager.TaskDescription(name, taskIcon, 0xff263238.toInt()))
    }

    override fun pauseForUpdate() {
        speechSession?.pause()
        translationSession?.pause()
        webView?.onPause()
        webView?.pauseTimers()
        webView?.visibility = android.view.View.INVISIBLE
    }

    override fun resumeAfterUpdate() {
        speechSession?.resume()
        translationSession?.resume()
        webView?.resumeTimers()
        webView?.onResume()
        webView?.visibility = android.view.View.VISIBLE
        webView?.reload()
    }

    override fun onDestroy() {
        speechSession?.close()
        translationSession?.close()
        if (isFinishing) (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelTask(taskId)
        activeId?.let { SubappSessionRegistry.unregister(it, this) }
        chooserCallback?.onReceiveValue(null); chooserCallback = null
        webView?.destroy(); webView = null; super.onDestroy()
    }
}
