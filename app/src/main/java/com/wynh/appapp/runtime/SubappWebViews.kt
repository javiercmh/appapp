package com.wynh.appapp.runtime

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wynh.appapp.bridge.NativeStorageBridge
import com.wynh.appapp.bridge.TokenGatedBridge
import com.wynh.appapp.workspace.WorkspaceManager
import org.json.JSONObject
import java.io.FileInputStream

/** Hardened WebView setup shared by the editor preview and the standalone runtime. */
object SubappWebViews {
    enum class Navigation { ALLOW, EXTERNAL, BLOCK }

    private val externalSchemes = setOf("http", "https", "mailto", "tel")

    const val NOT_FOUND_HTML = "<!doctype html><html><body><h3>404 Not Found</h3><p>This app file does not exist.</p></body></html>"

    fun host(subappId: String) = "$subappId.app.local"
    fun origin(subappId: String) = "https://${host(subappId)}"

    /**
     * Main frames may only show the subapp's own HTTPS origin. A deliberate (gesture, non-redirect)
     * web, mail or phone link leaves for another app; everything else is dropped. Subframes never
     * receive the bridge token, so their navigation is left to the page.
     */
    fun decide(scheme: String?, host: String?, subappId: String, mainFrame: Boolean, gesture: Boolean, redirect: Boolean): Navigation {
        val s = scheme?.lowercase()
        if (host.equals(host(subappId), ignoreCase = true)) return if (s == "https") Navigation.ALLOW else Navigation.BLOCK
        if (!mainFrame) return Navigation.ALLOW
        return if (gesture && !redirect && s in externalSchemes) Navigation.EXTERNAL else Navigation.BLOCK
    }

    /**
     * Content-Security-Policy for subapps without the network grant. It keeps everything on the
     * subapp's own origin (plus data and blob URLs) and blocks remote scripts, frames, fetches,
     * WebSockets and form posts. Inline code stays allowed because subapps rely on it.
     */
    fun offlinePolicy(subappId: String): String {
        val own = "'self' ${origin(subappId)}"
        return "default-src $own data: blob: 'unsafe-inline' 'unsafe-eval'; connect-src $own data: blob:; " +
            "frame-src $own data: blob:; form-action $own; base-uri $own; object-src 'none'"
    }

    /** True for hosts a network-granted subapp must still never reach: loopback, private and link-local literals. */
    fun isLocalNetworkHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return true
        if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() }
        if (v4 != null && v4.all { it != null && it in 0..255 }) {
            val (a, b) = v4[0]!! to v4[1]!!
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) || (a == 100 && b in 64..127)
        }
        if (h.contains(':')) return h == "::1" || h == "::" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("::ffff")
        return false
    }

    /** Entry document from manifest.json `main`, falling back to index.html. */
    fun entryPath(manager: WorkspaceManager): String {
        val main = try { JSONObject(manager.readFile("manifest.json")).optString("main", "index.html").trim().removePrefix("/") }
        catch (_: Exception) { "index.html" }
        return if (main.isNotBlank() && manager.getFile(main).isFile) main else "index.html"
    }

    fun entryUrl(subappId: String, manager: WorkspaceManager) = "${origin(subappId)}/${entryPath(manager)}"

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(web: WebView, cacheMode: Int) = web.settings.run {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        this.cacheMode = cacheMode
    }

    /**
     * Exposes [bridge] to documents of the subapp origin only (see [TokenGatedBridge]). Must run
     * before the first load. Without document-start scripts the bridge stays unavailable.
     */
    @SuppressLint("JavascriptInterface")
    fun installStorageBridge(web: WebView, subappId: String, bridge: NativeStorageBridge, log: (String) -> Unit): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            log("[Bridge Error] This WebView cannot restrict the storage bridge to the app origin, so AndroidStorage is unavailable. Update Android System WebView.")
            return false
        }
        val token = TokenGatedBridge.newToken()
        WebViewCompat.addDocumentStartJavaScript(web, TokenGatedBridge.shimScript(token, bridge.jsMethods), setOf(origin(subappId)))
        web.addJavascriptInterface(TokenGatedBridge(token, bridge.jsMethods, log), TokenGatedBridge.INTERFACE_NAME)
        return true
    }
}

/**
 * Serves only the subapp's workspace on its origin and enforces [SubappWebViews.decide]. Subclasses
 * that override [onPageStarted] must call super, which reloads the app if a navigation that
 * bypasses [shouldOverrideUrlLoading] (such as a form POST) committed a foreign main frame.
 */
open class SubappWebViewClient(
    private val context: Context,
    private val subappId: String,
    private val manager: () -> WorkspaceManager,
    private val log: (String) -> Unit,
    private val networkAllowed: () -> Boolean = { false }
) : WebViewClient() {
    private val reportedHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        return when (SubappWebViews.decide(uri.scheme, uri.host, subappId, request.isForMainFrame, request.hasGesture(), request.isRedirect)) {
            SubappWebViews.Navigation.ALLOW -> false
            SubappWebViews.Navigation.EXTERNAL -> {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    log("[WebView] No app can open ${uri.scheme} links.")
                }
                true
            }
            SubappWebViews.Navigation.BLOCK -> {
                log("[WebView] Blocked navigation away from the app: ${uri.scheme}://${uri.host.orEmpty()}")
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val uri = url?.let(android.net.Uri::parse) ?: return
        if (view == null || url == "about:blank") return
        if (SubappWebViews.decide(uri.scheme, uri.host, subappId, mainFrame = true, gesture = false, redirect = false) != SubappWebViews.Navigation.ALLOW) {
            log("[WebView] Left the app origin; reloading the app.")
            view.stopLoading()
            view.loadUrl(SubappWebViews.entryUrl(subappId, manager()))
        }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url ?: return null
        if (!url.host.equals(SubappWebViews.host(subappId), ignoreCase = true)) return foreignRequest(url)
        if (!url.scheme.equals("https", ignoreCase = true)) return notFound()
        val workspace = manager()
        val path = url.path.orEmpty().removePrefix("/").ifBlank { SubappWebViews.entryPath(workspace) }
        val file = workspace.getFile(path)
        if (file.isFile) return served(WorkspaceManager.getMimeType(path), FileInputStream(file))
        log("[WebView Error] Workspace file not found: $path")
        return notFound()
    }

    /** Own-origin files; subapps without the network grant also get an offline Content-Security-Policy. */
    private fun served(mime: String, body: java.io.InputStream): WebResourceResponse {
        val headers = if (networkAllowed()) emptyMap() else mapOf("Content-Security-Policy" to SubappWebViews.offlinePolicy(subappId))
        return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, body)
    }

    /** Requests to other hosts: refused without the network grant; local-network targets are always refused. */
    private fun foreignRequest(url: android.net.Uri): WebResourceResponse? {
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val reason = when {
            !networkAllowed() -> "this app has no internet permission (library: app menu → Permissions)"
            SubappWebViews.isLocalNetworkHost(url.host) -> "local-network addresses are never reachable from apps"
            else -> return null
        }
        if (reportedHosts.add(url.host.orEmpty())) log("[Network] Blocked request to ${url.host}: $reason")
        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), "".byteInputStream())
    }

    private fun notFound() = WebResourceResponse(
        "text/html", "UTF-8", 404, "Not Found", emptyMap(), SubappWebViews.NOT_FOUND_HTML.byteInputStream()
    )
}
