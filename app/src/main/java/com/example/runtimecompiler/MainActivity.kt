package com.example.runtimecompiler

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.runtimecompiler.bridge.NativeStorageBridge
import com.example.runtimecompiler.databinding.ActivityMainBinding
import com.example.runtimecompiler.templates.DefaultWebApp
import com.example.runtimecompiler.workspace.WorkspaceFile
import com.example.runtimecompiler.workspace.WorkspaceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageBridge: NativeStorageBridge
    private lateinit var workspaceManager: WorkspaceManager

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

        appendLog("[System] AppApp initialized with Edge-to-Edge & Multi-File Workspace.")

        // Initialize Workspace Manager
        workspaceManager = WorkspaceManager(this) { msg ->
            runOnUiThread { appendLog(msg) }
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

                // Intercept https://app.local/* requests to serve local workspace files
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

        // Reset Workspace to Default Starter Template
        binding.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reset AppApp Workspace?")
                .setMessage("This will restore index.html, style.css, app.js, and manifest.json to the default starter template.")
                .setPositiveButton("Reset") { _, _ ->
                    workspaceManager.resetWorkspace()
                    reloadApp()
                    Toast.makeText(this, "Workspace reset to default", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
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
        val btnResetFile = dialog.findViewById<MaterialButton>(R.id.editor_btn_reset_file)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.editor_btn_close)
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

        // Cache unsaved edits per file in-memory
        val unsavedEdits = mutableMapOf<String, String>()
        val files = workspaceManager.listFiles().toMutableList()
        for (f in files) {
            unsavedEdits[f.name] = workspaceManager.readFile(f.name)
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
                        // Save current code to cache
                        unsavedEdits[activeFileName] = codeInput.text.toString()
                        updateEditorContent(file.name)
                        rebuildTabs()
                    }
                }

                tabsContainer.addView(tabView)
            }
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

        // Reset Current File
        btnResetFile.setOnClickListener {
            val defaultContent = when (activeFileName) {
                "index.html" -> DefaultWebApp.DEFAULT_INDEX_HTML
                "style.css" -> DefaultWebApp.DEFAULT_STYLE_CSS
                "app.js" -> DefaultWebApp.DEFAULT_APP_JS
                "manifest.json" -> DefaultWebApp.DEFAULT_MANIFEST_JSON
                else -> ""
            }
            codeInput.setText(defaultContent)
            unsavedEdits[activeFileName] = defaultContent
            Toast.makeText(this, "Reset '$activeFileName'", Toast.LENGTH_SHORT).show()
        }

        // New File Creation
        btnNewFile.setOnClickListener {
            unsavedEdits[activeFileName] = codeInput.text.toString()

            val input = EditText(this).apply {
                hint = "filename.js / style.css / data.json"
                setSingleLine()
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Create New File")
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
                .setTitle("Delete File?")
                .setMessage("Are you sure you want to delete '$activeFileName'?")
                .setPositiveButton("Delete") { _, _ ->
                    val fileToDelete = activeFileName
                    if (workspaceManager.deleteFile(fileToDelete)) {
                        unsavedEdits.remove(fileToDelete)
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

            // Persist all edited files to disk
            for ((name, content) in unsavedEdits) {
                workspaceManager.writeFile(name, content)
            }

            reloadApp()
            Toast.makeText(this, "AppApp reloaded successfully!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        // Initial setup
        updateEditorContent(activeFileName)
        rebuildTabs()

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
