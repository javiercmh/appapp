package com.example.runtimecompiler

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.runtimecompiler.bridge.NativeStorageBridge
import com.example.runtimecompiler.databinding.ActivityMainBinding
import com.example.runtimecompiler.editor.ImageCropManager
import com.example.runtimecompiler.editor.SearchHelper
import com.example.runtimecompiler.editor.SyntaxHighlighter
import com.example.runtimecompiler.settings.AppSettingsManager
import com.example.runtimecompiler.workspace.WorkspaceFile
import com.example.runtimecompiler.workspace.WorkspaceHistoryManager
import com.example.runtimecompiler.workspace.WorkspaceManager
import com.example.runtimecompiler.workspace.WorkspacePackageManager
import com.example.runtimecompiler.workspace.WorkspaceSnapshot
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppConfigData(
    val name: String,
    val description: String,
    val iconFileName: String?
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageBridge: NativeStorageBridge
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var historyManager: WorkspaceHistoryManager
    private lateinit var settingsManager: AppSettingsManager

    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var pendingExportFiles: Set<String>? = null
    private var onImportCompletedCallback: (() -> Unit)? = null
    private var onIconUpdatedCallback: (() -> Unit)? = null

    /**
     * Held between `onShowFileChooser` and the picker result. WebView requires this callback to be
     * invoked exactly once — with null on cancel — or the originating `<input type="file">` is
     * permanently stuck and will never open a chooser again.
     */
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val saveZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val filesToExport = pendingExportFiles
        if (uri != null && filesToExport != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    WorkspacePackageManager.exportToOutputStream(workspaceManager, filesToExport, os)
                }
                appendLog("[Package] Saved ${filesToExport.size} files to ZIP via Storage Access Framework.")
                Toast.makeText(this, getString(R.string.export_success, filesToExport.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                appendLog("[Package Error] Failed to write ZIP: ${e.message}")
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportFiles = null
    }

    private val importZipLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = WorkspacePackageManager.importFromZip(this, uri, workspaceManager, historyManager)
            result.onSuccess { importedList ->
                appendLog("[Package] Successfully imported ${importedList.size} files from ZIP: ${importedList.joinToString(", ")}")
                Toast.makeText(this, getString(R.string.import_success, importedList.size), Toast.LENGTH_LONG).show()
                onImportCompletedCallback?.invoke()
            }.onFailure { err ->
                appendLog("[Package Error] ZIP Import failed: ${err.message}")
                Toast.makeText(this, "${getString(R.string.import_error)}: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
        onImportCompletedCallback = null
    }

    private val pickIconLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            ImageCropManager.openCropper(this, uri) { croppedBitmap ->
                try {
                    val iconFile = workspaceManager.getFile("icon.png")
                    FileOutputStream(iconFile).use { out ->
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    val currentConfig = loadAppConfig()
                    saveAppConfig(currentConfig.copy(iconFileName = "icon.png"))
                    appendLog("[Workspace] Updated app icon: icon.png")
                    Toast.makeText(this, "Custom icon updated", Toast.LENGTH_SHORT).show()
                    onIconUpdatedCallback?.invoke()
                } catch (e: Exception) {
                    appendLog("[Workspace Error] Failed to save icon: ${e.message}")
                    Toast.makeText(this, "Failed to save icon: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Backs `<input type="file">` inside the runtime web app. Registered as property initialisers
    // (i.e. before STARTED), matching pickIconLauncher above.
    private val webFileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            deliverFileChooserResult(if (uri != null) arrayOf(uri) else null)
        }

    private val webFileChooserMultiLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            deliverFileChooserResult(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    /**
     * Single delivery point for a pending file chooser. Idempotent: clears the field before
     * invoking, so a second call is a no-op rather than a double-delivery.
     */
    private fun deliverFileChooserResult(uris: Array<Uri>?) {
        val callback = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null
        try {
            callback.onReceiveValue(uris)
        } catch (e: Exception) {
            // The page may have navigated or reloaded while the picker was open.
            appendLog("[WebView] Stale file chooser callback ignored: ${e.message}")
        }
    }

    /**
     * Collapses an `<input accept="...">` list into the single MIME string GetContent takes.
     * Falls back to a family wildcard when the types agree, and to a full wildcard when they don't.
     */
    private fun resolveChooserMimeType(acceptTypes: Array<String>?): String {
        val raw = acceptTypes?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (raw.isEmpty()) return "*/*"
        val mimes = raw.map { type ->
            if (type.startsWith(".")) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.removePrefix(".").lowercase()) ?: "*/*"
            } else {
                type
            }
        }
        val firstFamily = mimes.first().substringBefore('/')
        return when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.substringBefore('/') == firstFamily } -> "$firstFamily/*"
            else -> "*/*"
        }
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                appendLog("[Permissions] Notification permission granted.")
            } else {
                appendLog("[Permissions] Notification permission denied.")
            }
            // The bridge's requestNotificationPermission() returns immediately because the native
            // prompt is async, so tell the web app the real outcome instead of making it poll.
            if (::binding.isInitialized) {
                binding.webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('app2:notifperm',{detail:{granted:$isGranted}}))",
                    null
                )
            }
        }

    fun promptNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NativeStorageBridge.CHANNEL_ID,
                NativeStorageBridge.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications posted from App² runtime web applications"
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable Edge-to-Edge display for notches, display cutouts, and status/navigation bars
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if launched from a Deployed Home Screen Shortcut
        val isStandalone = intent.getBooleanExtra("EXTRA_STANDALONE_MODE", false)
        if (isStandalone) {
            binding.appBarLayout.visibility = View.GONE
        }

        // Handle dynamic system window insets (Notches, Cutouts, Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (binding.appBarLayout.visibility != View.GONE) {
                binding.appBarLayout.updatePadding(
                    top = insets.top,
                    left = insets.left,
                    right = insets.right
                )
                binding.webView.updatePadding(
                    top = 0,
                    bottom = insets.bottom,
                    left = insets.left,
                    right = insets.right
                )
            } else {
                binding.webView.updatePadding(
                    top = insets.top,
                    bottom = insets.bottom,
                    left = insets.left,
                    right = insets.right
                )
            }
            windowInsets
        }

        // Initialize Workspace Manager
        workspaceManager = WorkspaceManager(this) { msg ->
            runOnUiThread { appendLog(msg) }
        }

        appendLog("[System] App² initialized with Edge-to-Edge & Unified Workspace.")

        // Initialize App Settings Manager
        settingsManager = AppSettingsManager(this)

        // Initialize Workspace History Manager
        historyManager = WorkspaceHistoryManager(this) { msg ->
            runOnUiThread { appendLog(msg) }
        }

        // Save initial snapshot if none exists
        if (historyManager.getSnapshots().isEmpty()) {
            historyManager.saveSnapshot(workspaceManager, "Starter Template")
        }

        // Initialize Native Storage & State Bridge
        storageBridge = NativeStorageBridge(
            context = this,
            onLogListener = { storageLog ->
                runOnUiThread { appendLog(storageLog) }
            },
            onRequestNotificationPermission = {
                runOnUiThread { promptNotificationPermission() }
            }
        )

        setupWebView()
        setupListeners()
        reloadApp()

        // Handle Back button for WebView history
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        // Enable JavaScript and modern web capabilities
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        // Hardware acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Inject Native Bridges as window.AndroidStorage, window.AndroidMemory, window.AndroidNotification
        webView.addJavascriptInterface(storageBridge, "AndroidStorage")
        webView.addJavascriptInterface(storageBridge, "AndroidMemory")
        webView.addJavascriptInterface(storageBridge, "AndroidNotification")

        // Intercept JavaScript console logs and errors
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = consoleMessage.messageLevel().name
                val msg = consoleMessage.message()
                val line = consoleMessage.lineNumber()
                val logEntry = "[$level] (line $line) $msg"
                appendLog(logEntry)
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            // Enables `<input type="file">` in the runtime web app. Without this override the
            // WebView silently ignores taps on file inputs.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false

                // Release any chooser still in flight so its <input> isn't stuck forever.
                deliverFileChooserResult(null)
                pendingFileChooserCallback = filePathCallback

                val mimeType = resolveChooserMimeType(fileChooserParams?.acceptTypes)
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                return try {
                    if (allowMultiple) {
                        webFileChooserMultiLauncher.launch(mimeType)
                    } else {
                        webFileChooserLauncher.launch(mimeType)
                    }
                    appendLog("[WebView] File chooser opened (accept=$mimeType, multiple=$allowMultiple)")
                    true
                } catch (e: Exception) {
                    // e.g. no picker app installed. Release the callback so the input stays usable.
                    appendLog("[WebView Error] Could not open file picker: ${e.message}")
                    deliverFileChooserResult(null)
                    true
                }
            }
        }

        // Handle page lifecycle and intercept workspace asset requests
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host ?: ""
                val scheme = url.scheme ?: ""

                // Intercept https://app.local/* requests to serve unified local workspace files
                if ((scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true))
                    && host.equals("app.local", ignoreCase = true)) {

                    var path = url.path ?: "/index.html"
                    if (path.isEmpty() || path == "/") {
                        val mainFile = try {
                            val manifestContent = workspaceManager.readFile("manifest.json")
                            if (manifestContent.isNotBlank()) {
                                JSONObject(manifestContent).optString("main", "index.html").trim().removePrefix("/")
                            } else "index.html"
                        } catch (_: Exception) {
                            "index.html"
                        }
                        path = "/${mainFile.ifBlank { "index.html" }}"
                    }
                    val fileName = path.removePrefix("/")
                    val file = workspaceManager.getFile(fileName)

                    if (file.exists() && file.isFile) {
                        val mimeType = WorkspaceManager.getMimeType(fileName)
                        val inputStream = FileInputStream(file)
                        return WebResourceResponse(mimeType, "UTF-8", inputStream)
                    } else {
                        appendLog("[WebView Error] Workspace file not found: $fileName")
                        val notFoundHtml = "<html><body><h3>404 Not Found</h3><p>Workspace file '$fileName' does not exist.</p></body></html>"
                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            ByteArrayInputStream(notFoundHtml.toByteArray(StandardCharsets.UTF_8))
                        )
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                appendLog("[WebView] Page render complete.")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val errorMsg = error?.description ?: "Unknown error"
                appendLog("[WebView Error] $errorMsg")
            }
        }
    }

    private fun setupListeners() {
        // App Settings Dialog
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // App Editor Hub Dialog
        binding.btnEditCode.setOnClickListener {
            showAppEditorDialog()
        }

        // Deploy / Add to Home Screen Pinned Shortcut
        binding.btnDeploy.setOnClickListener {
            deployToHomeScreen()
        }

        // Top Bar Refresh Button: Refresh site & clear cache
        binding.btnReset.setOnClickListener {
            binding.webView.clearCache(true)
            reloadApp()
            Toast.makeText(this, "Site refreshed & cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val isStandalone = intent.getBooleanExtra("EXTRA_STANDALONE_MODE", false)
        binding.appBarLayout.visibility = if (isStandalone) View.GONE else View.VISIBLE
        if (isStandalone) {
            reloadApp()
        }
    }

    /**
     * Reads app identity configuration from manifest.json.
     */
    private fun loadAppConfig(): AppConfigData {
        var name = "App² Project"
        var description = "On-device app built with App²"
        var iconFileName: String? = null

        try {
            val manifestContent = workspaceManager.readFile("manifest.json")
            if (manifestContent.isNotBlank()) {
                val json = JSONObject(manifestContent)
                val rawName = json.optString("name", json.optString("short_name", name))
                name = if (rawName == "AppApp Project" || rawName == "AppApp" || rawName.isBlank()) "App² Project" else rawName

                val rawDesc = json.optString("description", description)
                description = if (rawDesc.contains("built with AppApp") || rawDesc.isBlank()) "On-device app built with App²" else rawDesc

                val iconsArray = json.optJSONArray("icons")
                if (iconsArray != null && iconsArray.length() > 0) {
                    val firstIcon = iconsArray.optJSONObject(0)
                    iconFileName = firstIcon?.optString("src")
                }
            }
        } catch (e: Exception) {
            appendLog("[Config Warning] Could not parse manifest.json: ${e.message}")
        }

        if (iconFileName == null && workspaceManager.getFile("icon.png").exists()) {
            iconFileName = "icon.png"
        }

        return AppConfigData(name, description, iconFileName)
    }

    /**
     * Writes app identity configuration back to manifest.json.
     */
    private fun saveAppConfig(config: AppConfigData) {
        try {
            val manifestContent = workspaceManager.readFile("manifest.json")
            val json = if (manifestContent.isNotBlank()) {
                try { JSONObject(manifestContent) } catch (_: Exception) { JSONObject() }
            } else JSONObject()

            json.put("name", config.name)
            json.put("short_name", config.name)
            json.put("description", config.description)
            if (!json.has("version")) json.put("version", "1.0.0")
            if (!json.has("main")) json.put("main", "index.html")

            if (config.iconFileName != null) {
                val iconArray = JSONArray()
                val iconObj = JSONObject().apply {
                    put("src", config.iconFileName)
                    put("sizes", "512x512")
                    put("type", "image/png")
                }
                iconArray.put(iconObj)
                json.put("icons", iconArray)
            } else {
                json.remove("icons")
            }

            workspaceManager.writeFile("manifest.json", json.toString(2))
            appendLog("[Config] Updated manifest.json for '${config.name}'")
        } catch (e: Exception) {
            appendLog("[Config Error] Failed to write manifest.json: ${e.message}")
        }
    }

    /**
     * Creates a pinned Home Screen shortcut for the current web app using manifest.json metadata and icon.
     */
    private fun deployToHomeScreen() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.shortcut_not_supported, Toast.LENGTH_LONG).show()
            appendLog("[Deploy Error] Device launcher does not support pinned shortcuts.")
            return
        }

        val config = loadAppConfig()
        val appName = config.name

        // Resolve custom workspace icon or default App² logo
        val iconFile = config.iconFileName?.let { workspaceManager.getFile(it) }
            ?: workspaceManager.getFile("icon.png").takeIf { it.exists() }

        val iconCompat = if (iconFile != null && iconFile.exists() && iconFile.isFile) {
            try {
                val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
                if (bitmap != null) {
                    IconCompat.createWithBitmap(bitmap)
                } else {
                    renderAppShortcutIcon()
                }
            } catch (_: Exception) {
                renderAppShortcutIcon()
            }
        } else {
            renderAppShortcutIcon()
        }

        // Build launch Intent with standalone mode flag
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("EXTRA_STANDALONE_MODE", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // Build and request pinned shortcut
        val shortcutId = "appapp_shortcut_${appName.lowercase().replace(Regex("[^a-z0-9_-]"), "_")}"
        val pinShortcutInfo = ShortcutInfoCompat.Builder(this, shortcutId)
            .setShortLabel(appName)
            .setLongLabel(appName)
            .setIcon(iconCompat)
            .setIntent(shortcutIntent)
            .build()

        val pinned = ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
        if (pinned) {
            appendLog("[Deploy] Requested Home Screen shortcut for '$appName'")
            Toast.makeText(this, getString(R.string.shortcut_requested, appName), Toast.LENGTH_SHORT).show()
        } else {
            appendLog("[Deploy Error] Failed to request pinned shortcut.")
            Toast.makeText(this, R.string.shortcut_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Renders a high-resolution adaptive icon bitmap featuring the App² logo for Home Screen shortcuts.
     */
    private fun renderAppShortcutIcon(): IconCompat {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Full-bleed background gradient
        val gradient = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            Color.parseColor("#0F172A"),
            Color.parseColor("#1E1B4B"),
            Shader.TileMode.CLAMP
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // 2. Subtle glowing circular backdrop behind the logo
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.36f, glowPaint)

        // 3. Draw App Logo vector in the center safe area
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_app_logo)?.mutate()
        if (drawable != null) {
            val iconSize = 240
            val left = (size - iconSize) / 2
            val top = (size - iconSize) / 2
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.draw(canvas)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            IconCompat.createWithAdaptiveBitmap(bitmap)
        } else {
            IconCompat.createWithBitmap(bitmap)
        }
    }

    /**
     * Loads the workspace project into the WebView, resolving the entry point from manifest.json.
     */
    fun reloadApp() {
        val entryPoint = try {
            val manifestContent = workspaceManager.readFile("manifest.json")
            if (manifestContent.isNotBlank()) {
                val json = JSONObject(manifestContent)
                val main = json.optString("main", "index.html").trim().removePrefix("/")
                if (main.isNotBlank() && workspaceManager.getFile(main).exists()) main else "index.html"
            } else {
                "index.html"
            }
        } catch (_: Exception) {
            "index.html"
        }

        appendLog("[Compiler] Loading App² workspace entry '$entryPoint' into runtime...")
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        binding.webView.clearCache(true)
        binding.webView.loadUrl("https://app.local/$entryPoint")
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val formattedLog = "$timestamp $message\n"
        logBuffer.append(formattedLog)
        if (::workspaceManager.isInitialized) {
            workspaceManager.appendToFile("app.log", formattedLog)
        }
    }



    /**
     * Opens the revamped App Editor Workspace Hub dialog (Full screen dimensions matching parent).
     */
    private fun showAppEditorDialog() {
        val dialog = Dialog(this, R.style.Theme_AppApp_FullScreenDialog)
        dialog.setContentView(R.layout.dialog_workspace_hub)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val rootContainer = dialog.findViewById<LinearLayout>(R.id.hub_root_container)
        val btnClose = dialog.findViewById<ImageButton>(R.id.hub_btn_close)
        val subtitleText = dialog.findViewById<TextView>(R.id.hub_subtitle)
        val btnHistory = dialog.findViewById<ImageButton>(R.id.hub_btn_history)
        val btnPackage = dialog.findViewById<ImageButton>(R.id.hub_btn_package)
        val btnRun = dialog.findViewById<MaterialButton>(R.id.hub_btn_run)

        val appConfigCard = dialog.findViewById<MaterialCardView>(R.id.hub_app_config_card)
        val appIconPreview = dialog.findViewById<ImageView>(R.id.hub_app_icon_preview)
        val appNameText = dialog.findViewById<TextView>(R.id.hub_app_name)
        val appDescText = dialog.findViewById<TextView>(R.id.hub_app_desc)
        val btnConfigureApp = dialog.findViewById<MaterialButton>(R.id.hub_btn_configure_app)

        val fileCountBadge = dialog.findViewById<TextView>(R.id.hub_file_count_badge)
        val btnNewFile = dialog.findViewById<MaterialButton>(R.id.hub_btn_new_file)
        val filesContainer = dialog.findViewById<LinearLayout>(R.id.hub_files_container)

        // Handle insets for Edge-to-Edge dialog
        dialog.window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                val statusBarInset = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime())
                rootContainer.updatePadding(
                    top = statusBarInset.top,
                    bottom = maxOf(statusBarInset.bottom, imeInset.bottom),
                    left = statusBarInset.left,
                    right = statusBarInset.right
                )
                insets
            }
        }

        fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
                else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }

        fun getFileCategoryInfo(name: String): Pair<String, String> {
            val lower = name.lowercase()
            val simple = lower.substringAfterLast('/')
            return when {
                simple == "index.html" -> Pair("🌐", "Web Entrypoint & Layout")
                simple == "style.css" -> Pair("🎨", "App Stylesheet")
                simple == "app.js" -> Pair("⚡", "App Entry Point & Bootstrap")
                simple == "bridge.js" -> Pair("🔌", "Native Bridge Wrapper")
                simple == "store.js" -> Pair("💾", "Data & Persistence Layer")
                simple == "ui.js" -> Pair("🖌️", "UI Rendering Layer")
                simple == "agents.md" -> Pair("🤖", "AI Agent Instructions")
                simple == "manifest.json" -> Pair("📦", "App Metadata & Manifest")
                simple == "icon.png" || simple == "app_icon.png" -> Pair("🖼️", "App Icon Asset")
                simple == "app.log" -> Pair("📜", "Console & Runtime Logs")
                simple.endsWith(".html") || simple.endsWith(".htm") -> Pair("🌐", "HTML View")
                simple.endsWith(".css") -> Pair("🎨", "Stylesheet")
                simple.endsWith(".js") || simple.endsWith(".mjs") -> Pair("⚡", "JavaScript Module")
                simple.endsWith(".json") -> Pair("📄", "JSON Data File")
                simple.endsWith(".md") -> Pair("📝", "Markdown Document")
                simple.endsWith(".png") || simple.endsWith(".jpg") || simple.endsWith(".svg") || simple.endsWith(".webp") -> Pair("🖼️", "Image Asset")
                else -> Pair("📄", "Workspace File")
            }
        }

        fun refreshAppCard() {
            val config = loadAppConfig()
            subtitleText.text = config.name
            appNameText.text = config.name
            appDescText.text = config.description

            val iconFile = workspaceManager.getFile("icon.png")
            if (iconFile.exists() && iconFile.isFile) {
                try {
                    val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
                    if (bmp != null) {
                        appIconPreview.setImageBitmap(bmp)
                    } else {
                        appIconPreview.setImageResource(R.drawable.ic_app_logo)
                    }
                } catch (_: Exception) {
                    appIconPreview.setImageResource(R.drawable.ic_app_logo)
                }
            } else {
                appIconPreview.setImageResource(R.drawable.ic_app_logo)
            }
        }

        data class DirectoryNode(
            val name: String,
            val fullPath: String,
            val subFolders: MutableMap<String, DirectoryNode> = sortedMapOf(),
            val files: MutableList<WorkspaceFile> = mutableListOf()
        ) {
            fun totalFiles(): Int = files.size + subFolders.values.sumOf { it.totalFiles() }
        }

        fun buildTree(files: List<WorkspaceFile>): DirectoryNode {
            val root = DirectoryNode("", "")
            for (file in files) {
                val parts = file.name.split('/')
                var current = root
                var currentPath = ""
                for (i in 0 until parts.size - 1) {
                    val folderName = parts[i]
                    currentPath = if (currentPath.isEmpty()) folderName else "$currentPath/$folderName"
                    current = current.subFolders.getOrPut(folderName) {
                        DirectoryNode(folderName, currentPath)
                    }
                }
                current.files.add(file)
            }
            return root
        }

        val inflater = LayoutInflater.from(this)
        lateinit var refreshFileList: () -> Unit

        fun renderDirectory(node: DirectoryNode, targetContainer: LinearLayout) {
            for ((_, subFolder) in node.subFolders) {
                val folderView = inflater.inflate(R.layout.item_workspace_folder, targetContainer, false)
                val folderHeader = folderView.findViewById<LinearLayout>(R.id.folder_header)
                val folderNameView = folderView.findViewById<TextView>(R.id.folder_name)
                val folderCountBadge = folderView.findViewById<TextView>(R.id.folder_count_badge)
                val folderCaret = folderView.findViewById<TextView>(R.id.folder_caret)
                val childrenContainer = folderView.findViewById<LinearLayout>(R.id.folder_children_container)

                folderNameView.text = "${subFolder.name}/"
                val count = subFolder.totalFiles()
                folderCountBadge.text = "$count ${if (count == 1) "file" else "files"}"

                var isExpanded = true
                folderHeader.setOnClickListener {
                    isExpanded = !isExpanded
                    childrenContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    folderCaret.text = if (isExpanded) "▼" else "▶"
                }

                renderDirectory(subFolder, childrenContainer)
                targetContainer.addView(folderView)
            }

            val sortedFiles = node.files.sortedWith { a, b ->
                val aName = a.name.substringAfterLast('/')
                val bName = b.name.substringAfterLast('/')
                val priority = listOf("index.html", "manifest.json", "AGENTS.md")
                val aIdx = priority.indexOf(aName)
                val bIdx = priority.indexOf(bName)
                if (aIdx != -1 && bIdx != -1) aIdx.compareTo(bIdx)
                else if (aIdx != -1) -1
                else if (bIdx != -1) 1
                else aName.compareTo(bName, ignoreCase = true)
            }

            for (file in sortedFiles) {
                val itemView = inflater.inflate(R.layout.item_workspace_file, targetContainer, false)
                val iconView = itemView.findViewById<TextView>(R.id.file_item_icon)
                val nameView = itemView.findViewById<TextView>(R.id.file_item_name)
                val catView = itemView.findViewById<TextView>(R.id.file_item_category)
                val sizeView = itemView.findViewById<TextView>(R.id.file_item_size)

                val (emoji, categoryDesc) = getFileCategoryInfo(file.name)
                iconView.text = emoji
                nameView.text = file.name.substringAfterLast('/')
                catView.text = categoryDesc
                sizeView.text = formatFileSize(file.size)

                itemView.setOnClickListener {
                    if (isImageFile(file.name)) {
                        showImagePreviewDialog(file.name) {
                            refreshFileList()
                            refreshAppCard()
                        }
                    } else {
                        showFileEditorDialog(file.name) {
                            refreshFileList()
                            refreshAppCard()
                        }
                    }
                }

                targetContainer.addView(itemView)
            }
        }

        refreshFileList = {
            filesContainer.removeAllViews()
            val files = workspaceManager.listFiles()
            fileCountBadge.text = "${files.size} files"

            val tree = buildTree(files)
            renderDirectory(tree, filesContainer)
        }

        // Close Hub
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        // Configure App Form
        val openConfigAction = View.OnClickListener {
            showAppConfigDialog {
                refreshAppCard()
                refreshFileList()
            }
        }
        btnConfigureApp.setOnClickListener(openConfigAction)
        appConfigCard.setOnClickListener(openConfigAction)

        // Version History
        btnHistory.setOnClickListener {
            showHistoryDialog {
                refreshAppCard()
                refreshFileList()
                reloadApp()
            }
        }

        // Share & Package
        btnPackage.setOnClickListener {
            showImportExportDialog {
                refreshAppCard()
                refreshFileList()
                reloadApp()
            }
        }

        // Run
        btnRun.setOnClickListener {
            historyManager.saveSnapshot(workspaceManager, "Run Snapshot")
            reloadApp()
            Toast.makeText(this, getString(R.string.app_reloaded), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // New File Creation
        btnNewFile.setOnClickListener {
            val input = EditText(this).apply {
                hint = "js/helper.js, css/theme.css, data.json"
                setSingleLine()
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_new_file)
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotBlank()) {
                        if (workspaceManager.createFile(newName, "")) {
                            refreshFileList()
                            Toast.makeText(this, "Created '$newName'", Toast.LENGTH_SHORT).show()
                            showFileEditorDialog(newName) {
                                refreshFileList()
                            }
                        } else {
                            Toast.makeText(this, "File already exists or invalid name", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshAppCard()
        refreshFileList()

        dialog.show()
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.endsWith(".ico") || lower.endsWith(".svg")
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Opens dedicated full-screen image preview dialog with dimensions, file stats, and delete/trash action.
     */
    private fun showImagePreviewDialog(fileName: String, onDismiss: () -> Unit) {
        val dialog = Dialog(this, R.style.Theme_AppApp_FullScreenDialog)
        dialog.setContentView(R.layout.dialog_image_preview)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val rootContainer = dialog.findViewById<LinearLayout>(R.id.image_preview_root)
        val btnBack = dialog.findViewById<ImageButton>(R.id.preview_btn_back)
        val titleView = dialog.findViewById<TextView>(R.id.preview_file_name_title)
        val statsBadge = dialog.findViewById<TextView>(R.id.preview_file_stats_badge)
        val btnDelete = dialog.findViewById<ImageButton>(R.id.preview_btn_delete)
        val imageView = dialog.findViewById<ImageView>(R.id.preview_image_view)
        val dimenView = dialog.findViewById<TextView>(R.id.preview_info_dimensions)
        val mimeView = dialog.findViewById<TextView>(R.id.preview_info_mime)
        val sizeView = dialog.findViewById<TextView>(R.id.preview_info_size)

        // Handle insets for Edge-to-Edge dialog
        dialog.window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                val statusBarInset = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime())
                rootContainer.updatePadding(
                    top = statusBarInset.top,
                    bottom = maxOf(statusBarInset.bottom, imeInset.bottom),
                    left = statusBarInset.left,
                    right = statusBarInset.right
                )
                insets
            }
        }

        titleView.text = fileName
        val file = workspaceManager.getFile(fileName)
        val mimeType = WorkspaceManager.getMimeType(fileName)
        val fileSizeFormatted = formatFileSize(file.length())
        mimeView.text = "MIME Type: $mimeType"
        sizeView.text = fileSizeFormatted

        if (file.exists() && file.isFile) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    val w = bitmap.width
                    val h = bitmap.height
                    statsBadge.text = "${w} × ${h} px • $fileSizeFormatted"
                    dimenView.text = "Resolution: ${w} × ${h} px"
                } else {
                    imageView.setImageResource(R.drawable.ic_app_logo)
                    statsBadge.text = fileSizeFormatted
                    dimenView.text = "Vector / SVG Asset"
                }
            } catch (_: Exception) {
                imageView.setImageResource(R.drawable.ic_app_logo)
                statsBadge.text = fileSizeFormatted
                dimenView.text = "Image Asset"
            }
        }

        btnBack.setOnClickListener {
            dialog.dismiss()
            onDismiss()
        }

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete_file)
                .setMessage(getString(R.string.delete_file_confirm, fileName))
                .setPositiveButton(R.string.discard) { _, _ ->
                    if (workspaceManager.deleteFile(fileName)) {
                        if (fileName == "icon.png") {
                            val currentConfig = loadAppConfig()
                            saveAppConfig(currentConfig.copy(iconFileName = null))
                            Toast.makeText(this, "Reset custom app icon to default", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Deleted '$fileName'", Toast.LENGTH_SHORT).show()
                        }
                        dialog.dismiss()
                        onDismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    /**
     * Opens dedicated single-file code editor with syntax highlighting, search auto-scrolling, checkmark save, and delete.
     * Dimensions match the parent screen 100%.
     */
    private fun showFileEditorDialog(fileName: String, onDismiss: () -> Unit) {
        val dialog = Dialog(this, R.style.Theme_AppApp_FullScreenDialog)
        dialog.setContentView(R.layout.dialog_file_editor)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val rootContainer = dialog.findViewById<LinearLayout>(R.id.file_editor_root)
        val btnBack = dialog.findViewById<ImageButton>(R.id.editor_btn_back)
        val fileNameTitle = dialog.findViewById<TextView>(R.id.editor_file_name_title)
        val statsBadge = dialog.findViewById<TextView>(R.id.editor_file_stats_badge)
        val btnSearch = dialog.findViewById<ImageButton>(R.id.editor_btn_search)
        val btnDelete = dialog.findViewById<ImageButton>(R.id.editor_btn_delete)
        val btnSave = dialog.findViewById<ImageButton>(R.id.editor_btn_save)

        val searchBar = dialog.findViewById<LinearLayout>(R.id.editor_search_bar)
        val searchInput = dialog.findViewById<EditText>(R.id.editor_search_input)
        val matchesCount = dialog.findViewById<TextView>(R.id.editor_search_matches_count)
        val btnSearchPrev = dialog.findViewById<ImageButton>(R.id.editor_btn_search_prev)
        val btnSearchNext = dialog.findViewById<ImageButton>(R.id.editor_btn_search_next)
        val btnSearchClose = dialog.findViewById<ImageButton>(R.id.editor_btn_search_close)

        val scrollView = dialog.findViewById<ScrollView>(R.id.editor_scroll_view)
        val codeInput = dialog.findViewById<EditText>(R.id.editor_code_input)

        // Handle insets for dialog
        dialog.window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                val statusBarInset = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime())
                rootContainer.updatePadding(
                    top = statusBarInset.top,
                    bottom = maxOf(statusBarInset.bottom, imeInset.bottom),
                    left = statusBarInset.left,
                    right = statusBarInset.right
                )
                insets
            }
        }

        fileNameTitle.text = fileName
        var initialContent = workspaceManager.readFile(fileName)
        codeInput.setText(initialContent)
        codeInput.textSize = settingsManager.editorFontSizeSp
        statsBadge.text = "${initialContent.length} chars"

        // Check if file is protected against deletion (only index.html and manifest.json are protected)
        val protectedFiles = setOf("index.html", "manifest.json")
        val isDeletable = !protectedFiles.contains(fileName) && fileName != "icon.png"
        btnDelete.visibility = if (isDeletable) View.VISIBLE else View.GONE

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete_file)
                .setMessage(getString(R.string.delete_file_confirm, fileName))
                .setPositiveButton(R.string.discard) { _, _ ->
                    if (workspaceManager.deleteFile(fileName)) {
                        Toast.makeText(this, "Deleted '$fileName'", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onDismiss()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Apply syntax highlighting
        SyntaxHighlighter.highlight(codeInput.text, fileName)

        val handler = Handler(Looper.getMainLooper())
        var highlightRunnable: Runnable? = null

        // Live text change listener for character count and debounced highlighting
        codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                statsBadge.text = "${s?.length ?: 0} chars"
            }
            override fun afterTextChanged(s: Editable?) {
                highlightRunnable?.let { handler.removeCallbacks(it) }
                highlightRunnable = Runnable {
                    if (s != null) {
                        SyntaxHighlighter.highlight(s, fileName)
                    }
                }
                handler.postDelayed(highlightRunnable!!, 400)
            }
        })

        // Search integration with auto-scrolling
        val searchHelper = SearchHelper(codeInput, scrollView) { current, total ->
            matchesCount.text = if (total == 0) getString(R.string.search_no_matches) else getString(R.string.search_match_count, current, total)
        }

        btnSearch.setOnClickListener {
            if (searchBar.visibility == View.VISIBLE) {
                searchBar.visibility = View.GONE
                searchHelper.clear()
            } else {
                searchBar.visibility = View.VISIBLE
                searchInput.requestFocus()
                searchHelper.search(searchInput.text.toString())
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchHelper.search(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSearchNext.setOnClickListener { searchHelper.nextMatch() }
        btnSearchPrev.setOnClickListener { searchHelper.prevMatch() }
        btnSearchClose.setOnClickListener {
            searchBar.visibility = View.GONE
            searchHelper.clear()
        }

        fun hasUnsavedChanges(): Boolean {
            return codeInput.text.toString() != initialContent
        }

        fun saveFile() {
            val content = codeInput.text.toString()
            workspaceManager.writeFile(fileName, content)
            initialContent = content
            Toast.makeText(this, getString(R.string.file_saved_toast, fileName), Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener {
            saveFile()
        }

        fun handleBack() {
            if (hasUnsavedChanges()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.discard_changes_title)
                    .setMessage(R.string.discard_changes_msg)
                    .setPositiveButton(R.string.discard) { _, _ ->
                        dialog.dismiss()
                        onDismiss()
                    }
                    .setNegativeButton(R.string.keep_editing, null)
                    .show()
            } else {
                dialog.dismiss()
                onDismiss()
            }
        }

        btnBack.setOnClickListener {
            handleBack()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                handleBack()
                true
            } else {
                false
            }
        }

        dialog.show()
    }

    /**
     * Opens visual App Configuration dialog for manifest.json and custom app icon.
     */
    private fun showAppConfigDialog(onConfigSaved: () -> Unit) {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_app_config)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val iconPreview = dialog.findViewById<ImageView>(R.id.config_app_icon_preview)
        val btnPickIcon = dialog.findViewById<MaterialButton>(R.id.config_btn_pick_icon)
        val btnResetIcon = dialog.findViewById<MaterialButton>(R.id.config_btn_reset_icon)
        val inputName = dialog.findViewById<EditText>(R.id.config_input_app_name)
        val inputDesc = dialog.findViewById<EditText>(R.id.config_input_app_desc)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.config_btn_cancel)
        val btnSave = dialog.findViewById<MaterialButton>(R.id.config_btn_save)

        val currentConfig = loadAppConfig()
        inputName.setText(currentConfig.name)
        inputDesc.setText(currentConfig.description)

        fun updateIconPreview() {
            val iconFile = workspaceManager.getFile("icon.png")
            if (iconFile.exists() && iconFile.isFile) {
                try {
                    val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
                    if (bmp != null) {
                        iconPreview.setImageBitmap(bmp)
                    } else {
                        iconPreview.setImageResource(R.drawable.ic_app_logo)
                    }
                } catch (_: Exception) {
                    iconPreview.setImageResource(R.drawable.ic_app_logo)
                }
            } else {
                iconPreview.setImageResource(R.drawable.ic_app_logo)
            }
        }

        updateIconPreview()

        btnPickIcon.setOnClickListener {
            onIconUpdatedCallback = {
                updateIconPreview()
            }
            pickIconLauncher.launch("image/*")
        }

        btnResetIcon.setOnClickListener {
            workspaceManager.deleteFile("icon.png")
            saveAppConfig(loadAppConfig().copy(iconFileName = null))
            updateIconPreview()
            Toast.makeText(this, "Reset to default App² logo", Toast.LENGTH_SHORT).show()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newName = inputName.text.toString().trim().ifBlank { "My App" }
            val newDesc = inputDesc.text.toString().trim().ifBlank { "On-device app built with App²" }
            val iconExists = workspaceManager.getFile("icon.png").exists()

            val updatedConfig = AppConfigData(
                name = newName,
                description = newDesc,
                iconFileName = if (iconExists) "icon.png" else null
            )
            saveAppConfig(updatedConfig)
            Toast.makeText(this, R.string.config_saved_toast, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onConfigSaved()
        }

        dialog.show()
    }

    /**
     * Opens version history snapshot dialog allowing user to revert to previous states.
     */
    private fun showHistoryDialog(onRestored: () -> Unit) {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_version_history)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val container = dialog.findViewById<LinearLayout>(R.id.history_snapshots_container)
        val emptyState = dialog.findViewById<TextView>(R.id.history_empty_state)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.history_dialog_btn_close)

        val snapshots = historyManager.getSnapshots()
        container.removeAllViews()

        if (snapshots.isEmpty()) {
            emptyState.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
            val inflater = LayoutInflater.from(this)

            for (snap in snapshots) {
                val itemView = inflater.inflate(R.layout.item_history_snapshot, container, false)
                val timeView = itemView.findViewById<TextView>(R.id.history_item_time)
                val detailsView = itemView.findViewById<TextView>(R.id.history_item_details)
                val btnRestore = itemView.findViewById<MaterialButton>(R.id.history_btn_restore)

                val isStarter = snap.id == WorkspaceHistoryManager.STARTER_TEMPLATE_SNAPSHOT_ID
                timeView.text = if (isStarter) "⭐ ${snap.formattedTime}" else snap.formattedTime
                detailsView.text = "${snap.fileCount} files • ${snap.label}"

                btnRestore.setOnClickListener {
                    val title = if (isStarter) "Reset to Starter Template?" else "Revert to Snapshot?"
                    val message = if (isStarter) {
                        "Reset workspace to the official latest Starter Template? Your code files will be replaced. Images, including your app icon and any photos, are kept."
                    } else {
                        "Revert workspace to state from ${snap.formattedTime}? Your code files will be replaced. Images are kept."
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(if (isStarter) "Reset" else "Revert") { _, _ ->
                            if (historyManager.restoreSnapshot(snap.id, workspaceManager)) {
                                val toastMsg = if (isStarter) "Reset to Official Starter Template" else "Reverted to ${snap.formattedTime}"
                                Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                onRestored()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                container.addView(itemView)
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Opens modal dialog for selective ZIP export, direct packaging & sharing, and ZIP import.
     */
    private fun showImportExportDialog(onWorkspaceUpdated: () -> Unit) {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_import_export)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val filesContainer = dialog.findViewById<LinearLayout>(R.id.package_files_container)
        val nameInput = dialog.findViewById<EditText>(R.id.package_name_input)
        val masterCheckbox = dialog.findViewById<MaterialCheckBox>(R.id.package_master_checkbox)
        val masterCheckboxRow = dialog.findViewById<LinearLayout>(R.id.package_master_checkbox_row)
        val btnShareZip = dialog.findViewById<MaterialButton>(R.id.package_btn_share_zip)
        val btnSaveZip = dialog.findViewById<MaterialButton>(R.id.package_btn_save_zip)
        val btnImportZip = dialog.findViewById<MaterialButton>(R.id.package_btn_import_zip)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.package_btn_close)

        // Attempt to prefill package name from manifest.json
        val defaultPackageName = try {
            val config = loadAppConfig()
            config.name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        } catch (_: Exception) {
            "my_app"
        }
        nameInput.setText(defaultPackageName)

        val workspaceFiles = workspaceManager.listFiles()
        val selectedFiles = workspaceFiles.map { it.name }.toMutableSet()
        val checkboxMap = mutableMapOf<String, MaterialCheckBox>()

        fun updateMasterCheckboxState() {
            masterCheckbox.checkedState = when {
                workspaceFiles.isEmpty() || selectedFiles.isEmpty() -> MaterialCheckBox.STATE_UNCHECKED
                selectedFiles.size == workspaceFiles.size -> MaterialCheckBox.STATE_CHECKED
                else -> MaterialCheckBox.STATE_INDETERMINATE
            }
        }

        fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
                else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }

        fun getFileEmoji(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".html") || lower.endsWith(".htm") -> "🌐"
                lower.endsWith(".css") -> "🎨"
                lower.endsWith(".js") || lower.endsWith(".mjs") -> "⚡"
                lower.endsWith(".json") -> "📦"
                else -> "📄"
            }
        }

        filesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (file in workspaceFiles) {
            val itemView = inflater.inflate(R.layout.item_export_file_checkbox, filesContainer, false)
            val iconView = itemView.findViewById<TextView>(R.id.export_item_icon)
            val nameView = itemView.findViewById<TextView>(R.id.export_item_name)
            val sizeView = itemView.findViewById<TextView>(R.id.export_item_size)
            val checkBox = itemView.findViewById<MaterialCheckBox>(R.id.export_item_checkbox)

            iconView.text = getFileEmoji(file.name)
            nameView.text = file.name
            sizeView.text = formatFileSize(file.size)
            checkBox.isChecked = true
            checkboxMap[file.name] = checkBox

            val toggleAction = {
                val newState = !checkBox.isChecked
                checkBox.isChecked = newState
                if (newState) selectedFiles.add(file.name) else selectedFiles.remove(file.name)
                updateMasterCheckboxState()
            }

            itemView.setOnClickListener { toggleAction() }
            checkBox.setOnClickListener {
                if (checkBox.isChecked) selectedFiles.add(file.name) else selectedFiles.remove(file.name)
                updateMasterCheckboxState()
            }

            filesContainer.addView(itemView)
        }

        val toggleMaster = {
            val shouldSelectAll = selectedFiles.size < workspaceFiles.size
            selectedFiles.clear()
            if (shouldSelectAll) {
                for (file in workspaceFiles) {
                    selectedFiles.add(file.name)
                    checkboxMap[file.name]?.isChecked = true
                }
            } else {
                for (cb in checkboxMap.values) {
                    cb.isChecked = false
                }
            }
            updateMasterCheckboxState()
        }

        masterCheckbox.setOnClickListener { toggleMaster() }
        masterCheckboxRow.setOnClickListener { toggleMaster() }

        updateMasterCheckboxState()

        btnShareZip.setOnClickListener {
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, R.string.export_no_files_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val rawName = nameInput.text?.toString() ?: ""
            val customPackageName = WorkspacePackageManager.sanitizePackageName(rawName, "my_app")

            try {
                val zipFile = WorkspacePackageManager.exportToZipFile(this, workspaceManager, selectedFiles, customPackageName)
                val contentUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    zipFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share_zip)))
                appendLog("[Package] Exported ${selectedFiles.size} files to '$customPackageName.zip' and triggered Sharesheet.")
            } catch (e: Exception) {
                appendLog("[Package Error] Export failed: ${e.message}")
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveZip.setOnClickListener {
            if (selectedFiles.isEmpty()) {
                Toast.makeText(this, R.string.export_no_files_selected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val rawName = nameInput.text?.toString() ?: ""
            val customPackageName = WorkspacePackageManager.sanitizePackageName(rawName, "my_app")

            pendingExportFiles = selectedFiles.toSet()
            saveZipLauncher.launch("$customPackageName.zip")
        }

        btnImportZip.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_confirm_title)
                .setMessage(R.string.import_confirm_msg)
                .setPositiveButton(R.string.import_action_proceed) { _, _ ->
                    onImportCompletedCallback = {
                        onWorkspaceUpdated()
                        dialog.dismiss()
                    }
                    importZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Opens the centralized App Settings dialog.
     */
    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 1. Version Retention Limit Buttons
        val btnHist2 = dialog.findViewById<MaterialButton>(R.id.settings_btn_history_2)
        val btnHist4 = dialog.findViewById<MaterialButton>(R.id.settings_btn_history_4)
        val btnHist6 = dialog.findViewById<MaterialButton>(R.id.settings_btn_history_6)
        val btnHist8 = dialog.findViewById<MaterialButton>(R.id.settings_btn_history_8)
        val btnHist10 = dialog.findViewById<MaterialButton>(R.id.settings_btn_history_10)

        val historyButtons = mapOf(
            2 to btnHist2,
            4 to btnHist4,
            6 to btnHist6,
            8 to btnHist8,
            10 to btnHist10
        )

        fun updateHistoryButtonsUI(selected: Int) {
            historyButtons.forEach { (count, btn) ->
                if (btn == null) return@forEach
                if (count == selected) {
                    btn.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
                } else {
                    btn.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_elevated))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
                }
            }
        }

        updateHistoryButtonsUI(settingsManager.maxHistorySnapshots)

        historyButtons.forEach { (count, btn) ->
            btn?.setOnClickListener {
                settingsManager.maxHistorySnapshots = count
                historyManager.pruneToMax(count)
                updateHistoryButtonsUI(count)
                Toast.makeText(this, "Version history retention set to $count", Toast.LENGTH_SHORT).show()
            }
        }

        // 2. Editor Font Size Buttons (Vertical Column)
        val btnFontSmall = dialog.findViewById<MaterialButton>(R.id.settings_btn_font_small)
        val btnFontNormal = dialog.findViewById<MaterialButton>(R.id.settings_btn_font_normal)
        val btnFontLarge = dialog.findViewById<MaterialButton>(R.id.settings_btn_font_large)

        val fontButtons = mapOf(
            AppSettingsManager.FONT_SIZE_SMALL to btnFontSmall,
            AppSettingsManager.FONT_SIZE_NORMAL to btnFontNormal,
            AppSettingsManager.FONT_SIZE_LARGE to btnFontLarge
        )

        fun updateFontButtonsUI(selectedSize: Float) {
            fontButtons.forEach { (size, btn) ->
                if (btn == null) return@forEach
                if (Math.abs(size - selectedSize) < 0.5f) {
                    btn.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.on_primary))
                } else {
                    btn.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_elevated))
                    btn.setTextColor(ContextCompat.getColor(this, R.color.on_surface_muted))
                }
            }
        }

        updateFontButtonsUI(settingsManager.editorFontSizeSp)

        fontButtons.forEach { (size, btn) ->
            btn?.setOnClickListener {
                settingsManager.editorFontSizeSp = size
                updateFontButtonsUI(size)
                Toast.makeText(this, "Editor font size updated", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Workspace Storage Footprint
        val tvStorage = dialog.findViewById<TextView>(R.id.settings_tv_storage_footprint)
        tvStorage?.text = "Workspace: ${settingsManager.getWorkspaceStorageSummary(workspaceManager)}"

        // 4. Clear Cache & Storage Button
        val btnClearCache = dialog.findViewById<MaterialButton>(R.id.settings_btn_clear_cache)
        btnClearCache?.setOnClickListener {
            binding.webView.clearCache(true)
            binding.webView.clearFormData()
            WebStorage.getInstance().deleteAllData()
            workspaceManager.writeFile("app.log", "[System] Logs and cache cleared.\n")
            logBuffer.clear()
            reloadApp()
            tvStorage?.text = "Workspace: ${settingsManager.getWorkspaceStorageSummary(workspaceManager)}"
            Toast.makeText(this, getString(R.string.settings_clear_cache_success), Toast.LENGTH_SHORT).show()
        }

        // 5. About App² & WebEngine Version Readout
        val tvAppVersion = dialog.findViewById<TextView>(R.id.settings_tv_app_version)
        val tvWebviewVersion = dialog.findViewById<TextView>(R.id.settings_tv_webview_version)
        tvAppVersion?.text = settingsManager.getAppVersion()
        tvWebviewVersion?.text = settingsManager.getWebViewEngineVersion()

        // 6. Danger Zone: Factory Reset App²
        val btnFactoryReset = dialog.findViewById<MaterialButton>(R.id.settings_btn_factory_reset)
        btnFactoryReset?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_reset_confirm_title)
                .setMessage(R.string.settings_reset_confirm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.settings_reset_confirm_action) { _, _ ->
                    performFullAppReset()
                    dialog.dismiss()
                }
                .show()
        }

        // 7. Close Button
        dialog.findViewById<MaterialButton>(R.id.settings_btn_close)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Performs a complete factory reset of App²:
     * - Deletes all workspace files and restores clean starter template
     * - Clears all history snapshots from disk
     * - Resets all App² preferences to factory defaults
     * - Wipes WebView cache, WebSQL, and storage
     */
    private fun performFullAppReset() {
        workspaceManager.factoryResetWorkspace()
        historyManager.clearAllHistory()
        settingsManager.resetSettingsToDefaults()

        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        binding.webView.clearHistory()
        WebStorage.getInstance().deleteAllData()

        logBuffer.clear()
        appendLog("[System] App² factory reset completed. Starter template restored.")

        reloadApp()

        Toast.makeText(this, getString(R.string.settings_reset_success), Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release any in-flight file chooser before tearing the WebView down.
        deliverFileChooserResult(null)
        binding.webView.destroy()
    }
}
