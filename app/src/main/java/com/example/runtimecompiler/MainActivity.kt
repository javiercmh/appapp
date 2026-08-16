package com.example.runtimecompiler

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.runtimecompiler.bridge.NativeStorageBridge
import com.example.runtimecompiler.databinding.ActivityMainBinding
import com.example.runtimecompiler.templates.DefaultWebApp
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageBridge: NativeStorageBridge

    private var currentHtmlSource: String = DefaultWebApp.DEFAULT_HTML
    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appendLog("[System] App initialized with Persistent State & Native File Access.")

        // Initialize Native Storage & State Bridge
        storageBridge = NativeStorageBridge(this) { storageLog ->
            runOnUiThread {
                appendLog(storageLog)
            }
        }

        setupWebView()
        setupListeners()
        compileAndRun(currentHtmlSource)

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

        // Enable JavaScript and modern web persistence
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

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

        // Handle page lifecycle and navigation
        webView.webViewClient = object : WebViewClient() {
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
        // Edit Code Dialog
        binding.btnEditCode.setOnClickListener {
            showCodeEditorDialog()
        }

        // Console & Storage Logs Dialog
        binding.btnLogs.setOnClickListener {
            showLogsDialog()
        }

        // Reset to Default Web App
        binding.btnReset.setOnClickListener {
            currentHtmlSource = DefaultWebApp.DEFAULT_HTML
            compileAndRun(currentHtmlSource)
            Toast.makeText(this, "Reset to default Web App", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Compiles and loads the given HTML/CSS/JS source into the WebView runtime.
     */
    fun compileAndRun(htmlSource: String) {
        currentHtmlSource = htmlSource
        appendLog("[Compiler] Compiling and injecting runtime source (${htmlSource.length} chars)...")
        binding.webView.loadDataWithBaseURL(
            "https://app.local/",
            htmlSource,
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun appendLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val formattedLog = "$timestamp $message\n"
        logBuffer.append(formattedLog)
    }

    /**
     * Opens in-app live code editor dialog.
     */
    private fun showCodeEditorDialog() {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_code_editor)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val codeInput = dialog.findViewById<EditText>(R.id.editor_code_input)
        val btnRun = dialog.findViewById<MaterialButton>(R.id.editor_btn_run)
        val btnReset = dialog.findViewById<MaterialButton>(R.id.editor_btn_reset)

        codeInput.setText(currentHtmlSource)

        btnRun.setOnClickListener {
            val newCode = codeInput.text.toString()
            if (newCode.isNotBlank()) {
                compileAndRun(newCode)
                Toast.makeText(this, "Compiled & Executed Live!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        btnReset.setOnClickListener {
            codeInput.setText(DefaultWebApp.DEFAULT_HTML)
            Toast.makeText(this, "Code reset in editor", Toast.LENGTH_SHORT).show()
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
