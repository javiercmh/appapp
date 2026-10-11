package com.wynh.appapp

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wynh.appapp.bridge.NativeStorageBridge
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.WorkspaceManager
import org.json.JSONObject
import java.io.FileInputStream

/** Standalone document task bound to the app ID encoded in its launch URI. */
class SubappRuntimeActivity : AppCompatActivity(), UpdatableSubappSession {
    private var speechSession: com.wynh.appapp.speech.SpeechSession? = null
    private var webView: WebView? = null
    private var activeId: String? = null
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
        activeId?.let { SubappSessionRegistry.unregister(it, this) }
        speechSession?.close()
        webView?.destroy()
        activeId = record.id
        val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
        updateTaskAppearance(manager)
        SubappSessionRegistry.register(record.id, this)
        val root = FrameLayout(this)
        val web = com.wynh.appapp.speech.SpeechWebView(this)
        speechSession = com.wynh.appapp.speech.SpeechSession(this, web, record.id).also { it.install() }
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
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        val bridge = NativeStorageBridge(
            this, record.id, record.workspaceDir,
            onRequestNotificationPermission = {
                if (android.os.Build.VERSION.SDK_INT >= 33) runOnUiThread {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )
        web.addJavascriptInterface(bridge, "AndroidStorage")
        web.addJavascriptInterface(bridge, "AndroidMemory")
        web.addJavascriptInterface(bridge, "AndroidNotification")
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.let {
            com.wynh.appapp.ai.AiWebMessageBridge(this, web, record.id, taskId, it).install()
        }
        com.wynh.appapp.updates.UpdateWebMessageBridge(this, web, record).install()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                speechSession?.pageChanged()
                super.onPageStarted(view, url, favicon)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                ViewCompat.requestApplyInsets(root)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return !(uri.scheme.equals("https", true) && uri.host.equals("${record.id}.app.local", true))
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                if (!u.host.equals("${record.id}.app.local", true)) return null
                var path = u.path.orEmpty().removePrefix("/").ifBlank {
                    try { JSONObject(manager.readFile("manifest.json")).optString("main", "index.html").trimStart('/') }
                    catch (_: Exception) { "index.html" }
                }
                val file = manager.getFile(path)
                if (!file.isFile) return WebResourceResponse("text/html", "UTF-8", "<h3>App file not found</h3>".byteInputStream())
                return WebResourceResponse(WorkspaceManager.getMimeType(path), "UTF-8", FileInputStream(file))
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
        val main = try { JSONObject(manager.readFile("manifest.json")).optString("main", "index.html").trimStart('/') }
        catch (_: Exception) { "index.html" }
        web.loadUrl("https://${record.id}.app.local/${main.ifBlank { "index.html" }}")
        NativeStorageBridge.reschedulePersistedAlarms(this, record.id)
    }

    override fun onResume() {
        speechSession?.resume()
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
        webView?.onPause()
        webView?.pauseTimers()
        webView?.visibility = android.view.View.INVISIBLE
    }

    override fun resumeAfterUpdate() {
        speechSession?.resume()
        webView?.resumeTimers()
        webView?.onResume()
        webView?.visibility = android.view.View.VISIBLE
        webView?.reload()
    }

    override fun onDestroy() {
        speechSession?.close()
        if (isFinishing) (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelTask(taskId)
        activeId?.let { SubappSessionRegistry.unregister(it, this) }
        chooserCallback?.onReceiveValue(null); chooserCallback = null
        webView?.destroy(); webView = null; super.onDestroy()
    }
}
