package com.example.runtimecompiler

import android.annotation.SuppressLint
import android.app.Dialog
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.runtimecompiler.bridge.NativeStorageBridge
import com.example.runtimecompiler.databinding.ActivityMainBinding
import com.example.runtimecompiler.workspace.WorkspaceFile
import com.example.runtimecompiler.workspace.WorkspaceHistoryManager
import com.example.runtimecompiler.workspace.WorkspaceManager
import com.example.runtimecompiler.workspace.WorkspaceSnapshot
import com.google.android.material.button.MaterialButton
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable Edge-to-Edge display for notches, display cutouts, and status/navigation bars
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle dynamic system window insets (Notches, Cutouts, Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.appBarLayout.updatePadding(
                top = insets.top,
                left = insets.left,
                right = insets.right
            )
            binding.webView.updatePadding(
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right
            )
            windowInsets
        }

        appendLog("[System] AppApp initialized with Edge-to-Edge & Unified Workspace.")

        // Initialize Workspace Manager
        workspaceManager = WorkspaceManager(this) { msg ->
            runOnUiThread { appendLog(msg) }
        }

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

        // Console & Storage Logs Dialog
        binding.btnLogs.setOnClickListener {
            showLogsDialog()
        }

        // Top Bar Refresh Button: Refresh site & clear cache
        binding.btnReset.setOnClickListener {
            binding.webView.clearCache(true)
            reloadApp()
            Toast.makeText(this, "Site refreshed & cache cleared", Toast.LENGTH_SHORT).show()
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
        val btnHistory = dialog.findViewById<MaterialButton>(R.id.editor_btn_history)
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
     * Opens runtime console and native storage telemetry logs dialog.
     */
    private fun showLogsDialog() {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_console_logs)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val logsView = dialog.findViewById<TextView>(R.id.logs_text_view)
        val scrollView = dialog.findViewById<ScrollView>(R.id.logs_scroll_view)
        val btnClear = dialog.findViewById<MaterialButton>(R.id.logs_btn_clear)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.logs_btn_close)

        logsView.text = logBuffer.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }

        btnClear.setOnClickListener {
            logBuffer.setLength(0)
            logsView.text = "[System] Logs cleared.\n"
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
