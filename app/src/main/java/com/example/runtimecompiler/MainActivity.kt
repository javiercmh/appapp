package com.example.runtimecompiler

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
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
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
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
import com.example.runtimecompiler.workspace.WorkspaceFile
import com.example.runtimecompiler.workspace.WorkspaceHistoryManager
import com.example.runtimecompiler.workspace.WorkspaceManager
import com.example.runtimecompiler.workspace.WorkspacePackageManager
import com.example.runtimecompiler.workspace.WorkspaceSnapshot
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageBridge: NativeStorageBridge
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var historyManager: WorkspaceHistoryManager

    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var pendingExportFiles: Set<String>? = null
    private var onImportCompletedCallback: (() -> Unit)? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable Edge-to-Edge display for notches, display cutouts, and status/navigation bars
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

        appendLog("[System] AppApp initialized with Edge-to-Edge & Unified Workspace.")

        // Initialize Workspace History Manager
        historyManager = WorkspaceHistoryManager(this) { msg ->
            runOnUiThread { appendLog(msg) }
        }

        // Save initial snapshot if none exists
        if (historyManager.getSnapshots().isEmpty()) {
            historyManager.saveSnapshot(workspaceManager, "Starter Template")
        }

        // Initialize Native Storage & State Bridge
        storageBridge = NativeStorageBridge(this) { storageLog ->
            runOnUiThread { appendLog(storageLog) }
        }

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

        // Inject Native Storage Bridge as window.AndroidStorage & window.AndroidMemory
        webView.addJavascriptInterface(storageBridge, "AndroidStorage")
        webView.addJavascriptInterface(storageBridge, "AndroidMemory")

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
                        path = "/index.html"
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
        // Edit Code Studio Dialog
        binding.btnEditCode.setOnClickListener {
            showMultiFileEditorDialog()
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
     * Creates a pinned Home Screen shortcut for the current web app using manifest.json metadata.
     */
    private fun deployToHomeScreen() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.shortcut_not_supported, Toast.LENGTH_LONG).show()
            appendLog("[Deploy Error] Device launcher does not support pinned shortcuts.")
            return
        }

        // 1. Read app metadata from manifest.json
        var fullAppName = "AppApp Project"
        var shortAppName = "AppApp"
        var customIconFileName: String? = null

        try {
            val manifestContent = workspaceManager.readFile("manifest.json")
            if (manifestContent.isNotBlank()) {
                val json = org.json.JSONObject(manifestContent)
                fullAppName = json.optString("name", fullAppName)
                shortAppName = json.optString("short_name", fullAppName)

                val iconsArray = json.optJSONArray("icons")
                if (iconsArray != null && iconsArray.length() > 0) {
                    val firstIcon = iconsArray.optJSONObject(0)
                    customIconFileName = firstIcon?.optString("src")
                }
            }
        } catch (e: Exception) {
            appendLog("[Deploy Warning] Could not parse manifest.json: ${e.message}")
        }

        // 2. Resolve icon (custom workspace file or crisp rendered Install Mobile icon)
        val iconFile = customIconFileName?.let { workspaceManager.getFile(it) }
            ?: workspaceManager.getFile("icon.png").takeIf { it.exists() }
            ?: workspaceManager.getFile("app_icon.png").takeIf { it.exists() }
            ?: workspaceManager.getFile("icon.jpg").takeIf { it.exists() }

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

        // 3. Build launch Intent with standalone mode flag
        val shortcutIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("EXTRA_STANDALONE_MODE", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // 4. Build and request pinned shortcut
        val shortcutId = "appapp_shortcut_${shortAppName.lowercase().replace(Regex("[^a-z0-9_-]"), "_")}"
        val pinShortcutInfo = ShortcutInfoCompat.Builder(this, shortcutId)
            .setShortLabel(shortAppName)
            .setLongLabel(fullAppName)
            .setIcon(iconCompat)
            .setIntent(shortcutIntent)
            .build()

        val pinned = ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
        if (pinned) {
            appendLog("[Deploy] Requested Home Screen shortcut for '$fullAppName' ($shortAppName)")
            Toast.makeText(this, getString(R.string.shortcut_requested, shortAppName), Toast.LENGTH_SHORT).show()
        } else {
            appendLog("[Deploy Error] Failed to request pinned shortcut.")
            Toast.makeText(this, R.string.shortcut_not_supported, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Renders a high-resolution adaptive icon bitmap featuring the Install Mobile icon for Home Screen shortcuts.
     */
    private fun renderAppShortcutIcon(): IconCompat {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Full-bleed background gradient (Deep Navy to Indigo)
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

        // 2. Subtle glowing circular backdrop behind the icon
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.36f, glowPaint)

        // 3. Draw Install Mobile vector in the center safe area
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_install_mobile)?.mutate()
        if (drawable != null) {
            drawable.setTint(Color.parseColor("#38BDF8"))
            val iconSize = 220
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
     * Loads the workspace project (https://app.local/index.html) into the WebView.
     */
    fun reloadApp() {
        appendLog("[Compiler] Loading AppApp workspace into runtime...")
        binding.webView.clearCache(true)
        binding.webView.loadUrl("https://app.local/index.html")
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
     * Opens in-app multi-file code editor dialog.
     */
    private fun showMultiFileEditorDialog() {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_code_editor)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val rootContainer = dialog.findViewById<LinearLayout>(R.id.editor_root_container)
        val tabsContainer = dialog.findViewById<LinearLayout>(R.id.editor_tabs_container)
        val activeFileLabel = dialog.findViewById<TextView>(R.id.editor_active_file_label)
        val fileStats = dialog.findViewById<TextView>(R.id.editor_file_stats)
        val codeInput = dialog.findViewById<EditText>(R.id.editor_code_input)
        val btnHistory = dialog.findViewById<ImageButton>(R.id.editor_btn_history)
        val btnPackage = dialog.findViewById<ImageButton>(R.id.editor_btn_package)
        val btnRun = dialog.findViewById<MaterialButton>(R.id.editor_btn_run)
        val btnNewFile = dialog.findViewById<ImageButton>(R.id.editor_btn_new_file)
        val btnDeleteFile = dialog.findViewById<ImageButton>(R.id.editor_btn_delete_file)

        // Handle insets for dialog (IME keyboard & cutouts)
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

        // Original on-disk content baseline (to detect unsaved changes)
        val initialDiskContent = mutableMapOf<String, String>()
        val unsavedEdits = mutableMapOf<String, String>()
        val files = workspaceManager.listFiles().toMutableList()

        for (f in files) {
            val content = workspaceManager.readFile(f.name)
            initialDiskContent[f.name] = content
            unsavedEdits[f.name] = content
        }

        var activeFileName = if (unsavedEdits.containsKey("index.html")) "index.html" else (files.firstOrNull()?.name ?: "index.html")

        fun getFileIcon(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".html") || lower.endsWith(".htm") -> "🌐"
                lower.endsWith(".css") -> "🎨"
                lower.endsWith(".js") || lower.endsWith(".mjs") -> "⚡"
                lower.endsWith(".json") -> "📦"
                lower.endsWith(".log") -> "📜"
                else -> "📄"
            }
        }

        fun updateEditorContent(fileName: String) {
            activeFileName = fileName
            activeFileLabel.text = fileName
            val content = unsavedEdits[fileName] ?: ""
            codeInput.setText(content)
            fileStats.text = "${content.length} chars"

            // Show delete button only for non-core files
            val currentFileObj = files.find { it.name == fileName }
            btnDeleteFile.visibility = if (currentFileObj != null && !currentFileObj.isCore) View.VISIBLE else View.GONE
        }

        fun rebuildTabs() {
            tabsContainer.removeAllViews()
            val inflater = LayoutInflater.from(this)

            for (file in files) {
                val tabView = inflater.inflate(R.layout.item_editor_tab, tabsContainer, false)
                val tabRoot = tabView.findViewById<LinearLayout>(R.id.tab_root)
                val tabIcon = tabView.findViewById<TextView>(R.id.tab_icon)
                val tabTitle = tabView.findViewById<TextView>(R.id.tab_title)

                tabIcon.text = getFileIcon(file.name)
                tabTitle.text = file.name

                val isSelected = file.name == activeFileName
                tabRoot.setBackgroundResource(
                    if (isSelected) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected
                )
                tabTitle.setTextColor(
                    resources.getColor(
                        if (isSelected) R.color.on_surface else R.color.on_surface_muted,
                        theme
                    )
                )

                tabView.setOnClickListener {
                    if (file.name != activeFileName) {
                        unsavedEdits[activeFileName] = codeInput.text.toString()
                        updateEditorContent(file.name)
                        rebuildTabs()
                    }
                }

                tabsContainer.addView(tabView)
            }
        }

        fun hasUnsavedChanges(): Boolean {
            unsavedEdits[activeFileName] = codeInput.text.toString()
            if (unsavedEdits.size != initialDiskContent.size) return true
            for ((key, value) in unsavedEdits) {
                if (initialDiskContent[key] != value) return true
            }
            return false
        }

        // Live text change listener to update stats and unsaved cache
        codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val len = s?.length ?: 0
                fileStats.text = "$len chars"
                unsavedEdits[activeFileName] = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Version History Dialog
        btnHistory.setOnClickListener {
            unsavedEdits[activeFileName] = codeInput.text.toString()
            showHistoryDialog {
                // On snapshot restored, reload editor content
                files.clear()
                files.addAll(workspaceManager.listFiles())
                initialDiskContent.clear()
                unsavedEdits.clear()
                for (f in files) {
                    val content = workspaceManager.readFile(f.name)
                    initialDiskContent[f.name] = content
                    unsavedEdits[f.name] = content
                }
                updateEditorContent("index.html")
                rebuildTabs()
                reloadApp()
            }
        }

        // Share & Package Dialog
        btnPackage.setOnClickListener {
            unsavedEdits[activeFileName] = codeInput.text.toString()
            for ((name, content) in unsavedEdits) {
                workspaceManager.writeFile(name, content)
            }
            initialDiskContent.clear()
            initialDiskContent.putAll(unsavedEdits)

            showImportExportDialog {
                // On workspace replaced by import, refresh editor state & tabs
                files.clear()
                files.addAll(workspaceManager.listFiles())
                initialDiskContent.clear()
                unsavedEdits.clear()
                for (f in files) {
                    val content = workspaceManager.readFile(f.name)
                    initialDiskContent[f.name] = content
                    unsavedEdits[f.name] = content
                }
                updateEditorContent("index.html")
                rebuildTabs()
                reloadApp()
            }
        }

        // New File Creation
        btnNewFile.setOnClickListener {
            unsavedEdits[activeFileName] = codeInput.text.toString()

            val input = EditText(this).apply {
                hint = "data.json / utils.js / style.css"
                setSingleLine()
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_new_file)
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotBlank()) {
                        if (workspaceManager.createFile(newName, "")) {
                            unsavedEdits[newName] = ""
                            files.clear()
                            files.addAll(workspaceManager.listFiles())
                            updateEditorContent(newName)
                            rebuildTabs()
                            Toast.makeText(this, "Created '$newName'", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "File already exists or invalid name", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Delete Custom File
        btnDeleteFile.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete_file)
                .setMessage("Are you sure you want to delete '$activeFileName'?")
                .setPositiveButton(R.string.discard) { _, _ ->
                    val fileToDelete = activeFileName
                    if (workspaceManager.deleteFile(fileToDelete)) {
                        unsavedEdits.remove(fileToDelete)
                        initialDiskContent.remove(fileToDelete)
                        files.clear()
                        files.addAll(workspaceManager.listFiles())
                        updateEditorContent("index.html")
                        rebuildTabs()
                        Toast.makeText(this, "Deleted '$fileToDelete'", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Compile & Run All Files
        btnRun.setOnClickListener {
            unsavedEdits[activeFileName] = codeInput.text.toString()

            // 1. Capture snapshot of pre-run state in history
            historyManager.saveSnapshot(workspaceManager, "Run Snapshot")

            // 2. Persist all edited files to disk
            for ((name, content) in unsavedEdits) {
                workspaceManager.writeFile(name, content)
            }

            reloadApp()
            Toast.makeText(this, "AppApp reloaded successfully!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Handle Back button with unsaved changes confirmation
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (hasUnsavedChanges()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.discard_changes_title)
                        .setMessage(R.string.discard_changes_msg)
                        .setPositiveButton(R.string.discard) { _, _ ->
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.keep_editing, null)
                        .show()
                    true
                } else {
                    dialog.dismiss()
                    true
                }
            } else {
                false
            }
        }

        // Initial setup
        updateEditorContent(activeFileName)
        rebuildTabs()

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

                timeView.text = snap.formattedTime
                detailsView.text = "${snap.fileCount} files • ${snap.label}"

                btnRestore.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Revert to Snapshot?")
                        .setMessage("Revert workspace to state from ${snap.formattedTime}? Current unsaved edits will be replaced.")
                        .setPositiveButton("Revert") { _, _ ->
                            if (historyManager.restoreSnapshot(snap.id, workspaceManager)) {
                                Toast.makeText(this, "Reverted to ${snap.formattedTime}", Toast.LENGTH_SHORT).show()
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
            val manifestContent = workspaceManager.readFile("manifest.json")
            if (manifestContent.isNotBlank()) {
                val json = org.json.JSONObject(manifestContent)
                val rawAppName = json.optString("name", json.optString("short_name", "my-app"))
                rawAppName.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
            } else "my-app"
        } catch (_: Exception) {
            "my-app"
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
            // If all files are currently selected, deselect all; otherwise, select all.
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
            val customPackageName = WorkspacePackageManager.sanitizePackageName(rawName, "my-app")

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
            val customPackageName = WorkspacePackageManager.sanitizePackageName(rawName, "my-app")

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



    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
    }
}
