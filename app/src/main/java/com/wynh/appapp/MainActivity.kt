package com.wynh.appapp

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import com.wynh.appapp.workspace.AppIdentityManager

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.wynh.appapp.bridge.NativeStorageBridge
import com.wynh.appapp.databinding.ActivityMainBinding
import com.wynh.appapp.editor.ImageCropManager
import com.wynh.appapp.editor.SearchHelper
import com.wynh.appapp.editor.SyntaxHighlighter
import com.wynh.appapp.settings.AppSettingsManager
import com.wynh.appapp.workspace.WorkspaceFile
import com.wynh.appapp.workspace.WorkspaceHistoryManager
import com.wynh.appapp.workspace.WorkspaceManager
import com.wynh.appapp.workspace.WorkspaceSnapshot
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.WorkspaceActionController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppConfigData(
    val name: String,
    val description: String,
    val iconFileName: String?
)

class MainActivity : AppCompatActivity(), UpdatableSubappSession {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storageBridge: NativeStorageBridge
    private val workspaceActions by lazy { androidx.lifecycle.ViewModelProvider(this)[com.wynh.appapp.workspace.WorkspaceActionController::class.java] }
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var historyManager: WorkspaceHistoryManager
    private lateinit var settingsManager: AppSettingsManager
    private lateinit var subapp: SubappRecord
    private lateinit var subappRepository: SubappRepository

    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var configuringRecord: SubappRecord? = null
    private var pendingConfigIcon: ByteArray? = null
    private var resetConfigIcon = false
    private var configNameDraft = ""
    private var configDescriptionDraft = ""
    private var hasConfigDraft = false
    private var configNameInput: EditText? = null
    private var configDescriptionInput: EditText? = null
    private var configIconPreview: ImageView? = null
    private val pickConfigIcon = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val record = configuringRecord
        if (uri != null && record != null) ImageCropManager.openCropper(this, uri) { bitmap ->
            pendingConfigIcon = bitmapToPng(bitmap)
            resetConfigIcon = false
            refreshConfigIconPreview(record)
        }
    }
    private var previewVisible = false
    private var filesRoot: View? = null

    // Screens an operation's completion may have to close or refresh; see onWorkspaceCompleted.
    private var refreshFiles: (() -> Unit)? = null
    private var editorDialog: Dialog? = null
    private var configDialog: Dialog? = null
    private var historyDialog: Dialog? = null


    /** Last system-bar + cutout insets, republished to the WebView as CSS custom properties. */
    private var lastInsets: Insets = Insets.NONE
    private var lastImeVisible: Boolean = false

    private val runtimeChooser = com.wynh.appapp.runtime.RuntimeFileChooser(this, ::appendLog)

    // Android refuses to show the runtime prompt again once it has been denied, so after a denial
    // the only way forward is the system notification settings screen.
    private var notificationPromptRefused = false

    // Set while the App Settings dialog is open so its permission row can re-render.
    private var onNotificationPermissionChanged: (() -> Unit)? = null
    private var onExactAlarmPermissionChanged: (() -> Unit)? = null

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                appendLog("[Permissions] Notification permission granted.")
            } else {
                notificationPromptRefused = true
                appendLog("[Permissions] Notification permission denied.")
            }
            onNotificationPermissionChanged?.invoke()
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

    // Shared with ScheduledNotificationReceiver, which may run in a cold process.
    private fun createNotificationChannel() = NativeStorageBridge.ensureNotificationChannel(this)

    private var runtimeSession: com.wynh.appapp.runtime.RuntimeSession? = null


    override fun onResume() {
        runtimeSession?.resume()
        super.onResume()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(true)
        // Catches a permission flipped in the system settings screen we sent the user to.
        onNotificationPermissionChanged?.invoke()
        onExactAlarmPermissionChanged?.invoke()
        if (::binding.isInitialized) {
            val granted = NativeStorageBridge.canScheduleExact(this)
            binding.webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('app2:alarmperm',{detail:{granted:$granted}}))",
                null
            )
        }
    }

    override fun onPause() {
        runtimeSession?.pause()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(false)
        super.onPause()
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    /** False on API 26-32 (no runtime prompt exists) and after the user has refused it once. */
    private fun canPromptForNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPromptRefused

    /**
     * Android WebView derives `env(safe-area-inset-*)` from the display cutout only — never from the
     * status or navigation bars — so the real insets are published to the page as CSS custom
     * properties. Subapps combine the two sources with `max()`; see AGENTS.md.
     */
    private fun pushInsetsToWebView() {
        if (!::binding.isInitialized) return
        val density = resources.displayMetrics.density
        // Locale.ROOT: a comma decimal separator would produce invalid CSS.
        fun cssPx(value: Int) = String.format(Locale.ROOT, "%.2fpx", value / density)
        // The app bar already consumes the top inset whenever it is visible.
        val top = if (binding.appBarLayout.visibility == View.GONE) lastInsets.top else 0
        val js = """
            (function(s){
              s.setProperty('--app-inset-top','${cssPx(top)}');
              s.setProperty('--app-inset-right','${cssPx(lastInsets.right)}');
              s.setProperty('--app-inset-bottom','${cssPx(lastInsets.bottom)}');
              s.setProperty('--app-inset-left','${cssPx(lastInsets.left)}');
            })(document.documentElement.style);
            window.dispatchEvent(new CustomEvent('app2:insets',{detail:{imeVisible:$lastImeVisible}}));
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable Edge-to-Edge display for notches, display cutouts, and status/navigation bars
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        createNotificationChannel()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if launched from a Deployed Home Screen Shortcut
        val isStandalone = intent.getBooleanExtra("EXTRA_STANDALONE_MODE", false)
        if (isStandalone) binding.appBarLayout.visibility = View.GONE

        // Handle dynamic system window insets (Notches, Cutouts, Status Bar, Navigation Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            lastImeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (binding.appBarLayout.visibility != View.GONE) {
                binding.appBarLayout.updatePadding(
                    top = insets.top,
                    left = insets.left,
                    right = insets.right
                )
            }
            // The WebView itself stays edge-to-edge with zero native padding; the subapp owns its
            // own padding and receives the raw insets as CSS custom properties instead.
            binding.editorContainer.updatePadding(top = insets.top, left = insets.left, right = insets.right,
                bottom = maxOf(insets.bottom, windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom))
            lastInsets = insets
            pushInsetsToWebView()
            windowInsets
        }

        // Initialize Workspace Manager
        subappRepository = SubappRepository(this)
        val requestedId = intent.getStringExtra(EXTRA_SUBAPP_ID)
        subapp = try {
            if (requestedId != null) subappRepository.resolve(requestedId) ?: error("App no longer available")
            else subappRepository.ensureMigrated().firstOrNull() ?: subappRepository.createStarter()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.library_recovery_title)
                .setMessage(e.message).setPositiveButton(R.string.library_open_runner) { _, _ ->
                    startActivity(Intent(this, SubappLibraryActivity::class.java)); finish()
                }.setCancelable(false).show()
            return
        }
        SubappSessionRegistry.register(subapp.id, this)
        workspaceManager = WorkspaceManager(this, { msg -> runOnUiThread { appendLog(msg) } }, subapp.workspaceDir)

        appendLog("[System] App² initialized with Edge-to-Edge & Unified Workspace.")

        // Initialize App Settings Manager
        settingsManager = AppSettingsManager(this)

        // Initialize Workspace History Manager
        historyManager = WorkspaceHistoryManager(this, { msg -> runOnUiThread { appendLog(msg) } }, subapp.directory)
        binding.toolbar.title = loadAppConfig().name

        // Initialize Native Storage & State Bridge
        storageBridge = NativeStorageBridge(
            context = this,
            subappId = subapp.id,
            appWorkspaceDir = subapp.workspaceDir,
            onLogListener = { storageLog ->
                runOnUiThread { appendLog(storageLog) }
            },
            onRequestNotificationPermission = {
                runOnUiThread { promptNotificationPermission() }
            },
            onSetSelectionMenuSuppressed = { suppressed ->
                runOnUiThread { binding.webView.selectionMenuSuppressed = suppressed }
            }
        )

        setupWebView()
        setupListeners()
        com.wynh.appapp.runtime.WorkspaceOperationUi.bind(this, ::onWorkspaceCompleted)
        if (!java.io.File(subapp.directory, "workspace_initial_snapshot.json").exists()) {
            workspaceActions.execute(subapp, "Preparing editor…", replacement = false) { manager, history -> history.ensureInitialVersion(manager) }
        }
        reloadApp()
        if (savedInstanceState != null) workspaceActions.editorDraft?.let { draft -> showFileEditorDialog(draft.fileName) { showFiles() } }
        if (savedInstanceState?.getBoolean("editor_preview") == true || isStandalone) showPreview() else showFiles()
        if (savedInstanceState != null) {
            val id = savedInstanceState.getString("config_record_id")
            configuringRecord = id?.let(subappRepository::resolve)
            pendingConfigIcon = savedInstanceState.getByteArray("config_icon")
            resetConfigIcon = savedInstanceState.getBoolean("config_reset_icon")
            configNameDraft = savedInstanceState.getString("config_name").orEmpty()
            configDescriptionDraft = savedInstanceState.getString("config_description").orEmpty()
            hasConfigDraft = savedInstanceState.getBoolean("config_has_draft")
            configuringRecord?.let(::showConfigure)
        }

        NativeStorageBridge.reschedulePersistedAlarms(this, subapp.id)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            binding.root.postDelayed({ if (!isFinishing && !isDestroyed) showSettingsDialog() }, 500)
            intent.removeExtra(EXTRA_OPEN_SETTINGS)
        }

        // Handle Back button for WebView history
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewVisible && !isStandalone) {
                    showFiles()
                } else if (previewVisible && binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() {
        runtimeSession = com.wynh.appapp.runtime.RuntimeSession(
            this, binding.webView, subapp, workspaceManager, storageBridge, runtimeChooser, ::appendLog,
            WebSettings.LOAD_NO_CACHE,
            ready = { binding.progressBar.visibility = View.GONE; pushInsetsToWebView() },
            progress = { value ->
                binding.progressBar.visibility = if (value in 1..99) View.VISIBLE else View.GONE
                binding.progressBar.progress = value
            }
        )
    }

    private fun setupListeners() {
        // App Editor Hub Dialog
        binding.btnEditCode.setOnClickListener {
            showFiles()
        }

        binding.btnHistory.setOnClickListener { showHistoryDialog(returnToFiles = false) }

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
        if (intent.getStringExtra(EXTRA_SUBAPP_ID) != subapp.id) {
            finish()
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

    private fun saveAppConfig(config: AppConfigData) {
        val iconChange = if (config.iconFileName == null) {
            com.wynh.appapp.workspace.AppIdentityManager.IconChange.Reset
        } else {
            com.wynh.appapp.workspace.AppIdentityManager.IconChange.Keep
        }
        val app = applicationContext
        val record = subapp
        workspaceActions.execute(record, "Saving app settings…", replacement = false, completion = settingsSaved(config.name)) { _, _ ->
            check(com.wynh.appapp.workspace.AppIdentityManager(app, record).save(config.name, config.description, iconChange)) { "Could not save app settings" }
        }
    }

    private fun settingsSaved(name: String) = WorkspaceActionController.Completion(WorkspaceActionController.Kind.SETTINGS_SAVED, name)

    /**
     * Every follow-up of a workspace operation lives here, not in a coroutine of the screen that
     * started it, so it also runs on the new instance after a rotation.
     */
    private fun onWorkspaceCompleted(completion: WorkspaceActionController.Completion) {
        when (completion.kind) {
            WorkspaceActionController.Kind.FILE_SAVED -> {
                // The saved text is now the baseline the open editor compares against.
                workspaceActions.editorDraft?.takeIf { it.fileName == completion.subject }
                    ?.let { workspaceActions.editorDraft = it.copy(initial = completion.payload) }
                Toast.makeText(this, getString(R.string.file_saved_toast, completion.subject), Toast.LENGTH_SHORT).show()
            }
            WorkspaceActionController.Kind.FILE_CREATED -> {
                refreshFiles?.invoke()
                Toast.makeText(this, "Created '${completion.subject}'", Toast.LENGTH_SHORT).show()
                showFileEditorDialog(completion.subject) { refreshFiles?.invoke() }
            }
            WorkspaceActionController.Kind.FILE_DELETED -> {
                if (completion.subject == "icon.png") {
                    saveAppConfig(loadAppConfig().copy(iconFileName = null))
                    Toast.makeText(this, "Reset custom app icon to default", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Deleted '${completion.subject}'", Toast.LENGTH_SHORT).show()
                }
                editorDialog?.dismiss()
                refreshFiles?.invoke()
            }
            WorkspaceActionController.Kind.PREVIEW_READY -> { reloadApp(); showPreview() }
            WorkspaceActionController.Kind.SETTINGS_SAVED -> {
                binding.toolbar.title = completion.subject
                AppIdentityManager(this, subapp).refreshPinnedShortcut()
                configDialog?.let { dialog ->
                    clearConfigDraft(); dialog.dismiss(); showFiles()
                    Toast.makeText(this, R.string.config_saved_toast, Toast.LENGTH_SHORT).show()
                }
            }
            WorkspaceActionController.Kind.RESTORED -> {
                historyDialog?.dismiss()
                Toast.makeText(this, R.string.history_restored, Toast.LENGTH_SHORT).show()
                reloadApp()
                if (completion.payload == RETURN_TO_FILES) showFiles()
            }
            WorkspaceActionController.Kind.RESET -> finishFullAppReset()
            WorkspaceActionController.Kind.UPDATED -> Unit // toast and sessions are handled by WorkspaceOperationUi and the registry
        }
    }

    /**
     * Loads the workspace project into the WebView, resolving the entry point from manifest.json.
     */
    fun reloadApp() {
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelTask(taskId)
        val entryPoint = com.wynh.appapp.runtime.SubappWebViews.entryPath(workspaceManager)

        appendLog("[Compiler] Loading App² workspace entry '$entryPoint' into runtime...")
        binding.webView.clearCache(true)
        binding.webView.loadUrl("https://${subapp.id}.app.local/$entryPoint")
    }

    private fun appendLog(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { appendLog(message) }; return }
        val timestamp = timeFormat.format(Date())
        val formattedLog = "$timestamp $message\n"
        logBuffer.append(formattedLog)
        if (::workspaceManager.isInitialized) {
            com.wynh.appapp.workspace.WorkspaceLog.append(applicationContext, workspaceManager.workspaceDir, formattedLog.trimEnd())
        }
    }



    /**
     * Displays the editor files screen.
     */
    private fun showFiles() {
        filesRoot?.let { binding.editorContainer.removeView(it) }
        val root = layoutInflater.inflate(R.layout.dialog_workspace_hub, binding.editorContainer, false)
        filesRoot = root
        binding.editorContainer.addView(root)
        val btnClose = root.findViewById<ImageButton>(R.id.hub_btn_close)
        val subtitleText = root.findViewById<TextView>(R.id.hub_subtitle)
        val btnRun = root.findViewById<MaterialButton>(R.id.hub_btn_run)
        val fileCountBadge = root.findViewById<TextView>(R.id.hub_file_count_badge)
        val btnNewFile = root.findViewById<MaterialButton>(R.id.hub_btn_new_file)
        val filesContainer = root.findViewById<LinearLayout>(R.id.hub_files_container)
        subtitleText.text = loadAppConfig().name
        root.findViewById<View>(R.id.hub_configure).setOnClickListener { showConfigure(subapp) }
        root.findViewById<View>(R.id.hub_history).setOnClickListener { showHistoryDialog(returnToFiles = true) }
        previewVisible = false
        binding.webView.visibility = View.GONE
        binding.editorContainer.visibility = View.VISIBLE
        binding.appBarLayout.visibility = View.GONE
        ViewCompat.requestApplyInsets(binding.root)

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
                val priority = listOf("index.html", "manifest.json", "AGENTS.md", "PACKAGING.md", "dev_server.py")
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
                        }
                    } else {
                        showFileEditorDialog(file.name) {
                            refreshFileList()
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
            finish()
        }

        // Run
        btnRun.setOnClickListener {
            workspaceActions.execute(subapp, "Preparing preview…", replacement = false,
                completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.PREVIEW_READY)) { manager, history ->
                check(history.saveSnapshot(manager, "Run Snapshot") != null) { "Could not save version history" }
            }
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
                        saveWorkspaceChange("Created $newName", WorkspaceActionController.Kind.FILE_CREATED, newName) { it.createFile(newName, "") }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshFiles = refreshFileList
        refreshFileList()
    }

    private fun showConfigure(record: SubappRecord) {
        val restoringDraft = hasConfigDraft
        configuringRecord = record
        val content = layoutInflater.inflate(R.layout.dialog_app_config, null)
        val dialog = Dialog(this)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val identity = AppIdentityManager(this, record)
        val existing = identity.load()
        val name = content.findViewById<EditText>(R.id.config_input_app_name)
        val description = content.findViewById<EditText>(R.id.config_input_app_desc)
        val iconPreview = content.findViewById<ImageView>(R.id.config_app_icon_preview)
        configNameInput = name
        configDescriptionInput = description
        configIconPreview = iconPreview
        name.setText(if (restoringDraft) configNameDraft else existing.name)
        description.setText(if (restoringDraft) configDescriptionDraft else existing.description)
        hasConfigDraft = true
        refreshConfigIconPreview(record)
        content.findViewById<MaterialButton>(R.id.config_btn_pick_icon).setOnClickListener { pickConfigIcon.launch("image/*") }
        content.findViewById<MaterialButton>(R.id.config_btn_reset_icon).setOnClickListener {
            pendingConfigIcon = null
            resetConfigIcon = true
            refreshConfigIconPreview(record)
        }
        content.findViewById<MaterialButton>(R.id.config_btn_cancel).setOnClickListener {
            clearConfigDraft()
            dialog.dismiss()
        }
        content.findViewById<MaterialButton>(R.id.config_btn_save).setOnClickListener {
            val newName = name.text.toString().trim()
            if (newName.isBlank()) {
                name.error = getString(R.string.library_name_required)
                return@setOnClickListener
            }
            name.error = null
            val iconChange = when {
                resetConfigIcon -> AppIdentityManager.IconChange.Reset
                pendingConfigIcon != null -> AppIdentityManager.IconChange.Replace(pendingConfigIcon!!)
                else -> AppIdentityManager.IconChange.Keep
            }
            val newDescription = description.text.toString().trim()
            val app = applicationContext
            workspaceActions.execute(record, "Saving app settings…", replacement = false, completion = settingsSaved(newName)) { _, _ ->
                check(AppIdentityManager(app, record).save(newName, newDescription, iconChange)) { "Could not save app settings" }
            }
        }
        dialog.setOnCancelListener { clearConfigDraft() }
        dialog.setOnDismissListener {
            if (configDialog === dialog) configDialog = null
            if (configuringRecord != null) clearConfigDraft()
        }
        configDialog = dialog
        dialog.show()
        dialog.window?.setLayout(
            minOf((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.density * 520).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun refreshConfigIconPreview(record: SubappRecord) {
        val preview = configIconPreview ?: return
        val bitmap = when {
            resetConfigIcon -> null
            pendingConfigIcon != null -> BitmapFactory.decodeByteArray(pendingConfigIcon, 0, pendingConfigIcon!!.size)
            else -> com.wynh.appapp.workspace.SubappMetadataLoader.load(record.workspaceDir).icon
        }
        if (bitmap == null) preview.setImageResource(R.mipmap.ic_launcher_round) else preview.setImageBitmap(bitmap)
    }

    private fun clearConfigDraft() {
        configuringRecord = null
        pendingConfigIcon = null
        resetConfigIcon = false
        configNameDraft = ""
        configDescriptionDraft = ""
        hasConfigDraft = false
        configNameInput = null
        configDescriptionInput = null
        configIconPreview = null
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray = java.io.ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        configuringRecord?.let { outState.putString("config_record_id", it.id) }
        outState.putByteArray("config_icon", pendingConfigIcon)
        outState.putBoolean("config_reset_icon", resetConfigIcon)
        outState.putString("config_name", configNameInput?.text?.toString() ?: configNameDraft)
        outState.putString("config_description", configDescriptionInput?.text?.toString() ?: configDescriptionDraft)
        outState.putBoolean("config_has_draft", configuringRecord != null)
        outState.putBoolean("editor_preview", previewVisible)
        super.onSaveInstanceState(outState)
    }

    private fun showPreview() {
        previewVisible = true
        binding.webView.visibility = View.VISIBLE
        binding.editorContainer.visibility = View.GONE
        binding.appBarLayout.visibility = if (intent.getBooleanExtra("EXTRA_STANDALONE_MODE", false)) View.GONE else View.VISIBLE
        ViewCompat.requestApplyInsets(binding.root)
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
                    saveWorkspaceChange("Deleted $fileName", WorkspaceActionController.Kind.FILE_DELETED, fileName) { it.deleteFile(fileName) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.setOnDismissListener { if (editorDialog === dialog) editorDialog = null }
        editorDialog = dialog
        dialog.show()
    }

    /**
     * Opens dedicated single-file code editor with syntax highlighting, search auto-scrolling, checkmark save, and delete.
     * Dimensions match the parent screen 100%.
     */
    private fun showFileEditorDialog(fileName: String, onDismiss: () -> Unit) {
        val loaded = runCatching { workspaceManager.files.readText(fileName, 16L * 1024 * 1024) }.getOrElse {
            Toast.makeText(this, R.string.editor_cannot_read, Toast.LENGTH_LONG).show(); return
        }
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
        // The last saved text is kept in the controller's draft, where a finished save updates it even
        // if this dialog was recreated meanwhile.
        fun savedText() = workspaceActions.editorDraft?.takeIf { it.fileName == fileName }?.initial ?: loaded
        val draft = workspaceActions.editorDraft?.takeIf { it.fileName == fileName }
        codeInput.setText(draft?.text ?: loaded)
        workspaceActions.editorDraft = WorkspaceActionController.EditorDraft(fileName, draft?.initial ?: loaded, codeInput.text.toString())
        codeInput.textSize = settingsManager.editorFontSizeSp
        statsBadge.text = "${codeInput.text.length} chars"

        // Check if file is protected against deletion (only index.html and manifest.json are protected)
        val protectedFiles = setOf("index.html", "manifest.json")
        val isDeletable = !protectedFiles.contains(fileName) && fileName != "icon.png"
        btnDelete.visibility = if (isDeletable) View.VISIBLE else View.GONE

        btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_delete_file)
                .setMessage(getString(R.string.delete_file_confirm, fileName))
                .setPositiveButton(R.string.discard) { _, _ ->
                    saveWorkspaceChange("Deleted $fileName", WorkspaceActionController.Kind.FILE_DELETED, fileName) { it.deleteFile(fileName) }
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
                workspaceActions.editorDraft = WorkspaceActionController.EditorDraft(fileName, savedText(), s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {
                highlightRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (s != null) {
                        SyntaxHighlighter.highlight(s, fileName)
                    }
                }
                highlightRunnable = runnable
                handler.postDelayed(runnable, 400)
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
            return codeInput.text.toString() != savedText()
        }

        fun saveFile() {
            val content = codeInput.text.toString()
            if (content == savedText()) return
            saveWorkspaceChange("Edited $fileName", WorkspaceActionController.Kind.FILE_SAVED, fileName, content) { it.writeFile(fileName, content) }
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

        // Android 13+ dispatches gesture/system Back without a KeyEvent. Route it through the
        // same unsaved-change guard instead of Dialog's default cancel-and-discard behavior.
        var nativeBack: Any? = null
        dialog.setOnShowListener {
            if (Build.VERSION.SDK_INT >= 33) nativeBack = registerDialogBackCallback(dialog) { handleBack() }
        }
        dialog.setOnDismissListener {
            if (Build.VERSION.SDK_INT >= 33) {
                unregisterDialogBackCallback(dialog, nativeBack)
                nativeBack = null
            }
            if (editorDialog === dialog) editorDialog = null
            if (!isChangingConfigurations) workspaceActions.editorDraft = null
        }
        editorDialog = dialog
        dialog.show()
    }

    @androidx.annotation.RequiresApi(33)
    private fun registerDialogBackCallback(dialog: Dialog, onBack: () -> Unit): android.window.OnBackInvokedCallback {
        val callback = android.window.OnBackInvokedCallback { onBack() }
        dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        return callback
    }

    @androidx.annotation.RequiresApi(33)
    private fun unregisterDialogBackCallback(dialog: Dialog, callback: Any?) {
        (callback as? android.window.OnBackInvokedCallback)?.let {
            dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
    }

    /**
     * Opens visual App Configuration dialog for manifest.json and custom app icon.
     */
    /**
     * Opens version history snapshot dialog allowing user to revert to previous states.
     */
    private fun saveWorkspaceChange(
        label: String, kind: WorkspaceActionController.Kind, subject: String, payload: String = "", change: (WorkspaceManager) -> Boolean
    ) = workspaceActions.change(subapp, label, WorkspaceActionController.Completion(kind, subject, payload), change)

    private fun showHistoryDialog(returnToFiles: Boolean) {
        lifecycleScope.launch {
            val snapshots = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { historyManager.getSnapshots() } }
            catch (e: Exception) { Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show(); return@launch }
            showHistorySnapshots(snapshots, returnToFiles)
        }
    }

    private fun showHistorySnapshots(snapshots: List<com.wynh.appapp.workspace.WorkspaceSnapshot>, returnToFiles: Boolean) {
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        historyDialog = dialog
        dialog.setOnDismissListener { if (historyDialog === dialog) historyDialog = null }
        dialog.setContentView(R.layout.dialog_version_history)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val container = dialog.findViewById<LinearLayout>(R.id.history_snapshots_container)
        val emptyState = dialog.findViewById<TextView>(R.id.history_empty_state)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.history_dialog_btn_close)

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

                val isInitial = snap.id == WorkspaceHistoryManager.INITIAL_SNAPSHOT_ID
                timeView.text = if (isInitial) "⭐ ${snap.formattedTime}" else snap.formattedTime
                detailsView.text = "${snap.fileCount} files • ${snap.label}"

                btnRestore.setOnClickListener {
                    val title = if (isInitial) "Restore Initial Version?" else "Revert to Snapshot?"
                    val message = if (isInitial) {
                        "Restore this subapp to its initial version (${snap.label})? Text files, including data and settings, will be replaced. Your current version will be saved first. Images, including your app icon and any photos, are kept."
                    } else "Revert workspace to state from ${snap.formattedTime}? Text files, including data and settings, will be replaced. Your current version will be saved first. Images are kept."
                    MaterialAlertDialogBuilder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(if (isInitial) "Restore" else "Revert") { _, _ ->
                            workspaceActions.execute(subapp, "Restoring version…",
                                completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.RESTORED, payload = if (returnToFiles) RETURN_TO_FILES else "")) { manager, history ->
                                check(history.restoreSnapshot(snap.id, manager)) { "Could not restore version" }
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

        // Editor Font Size Buttons (Vertical Column)
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
            WebStorage.getInstance().deleteOrigin("https://${subapp.id}.app.local")
            workspaceManager.writeFile("app.log", "[System] Logs and cache cleared.\n")
            logBuffer.clear()
            reloadApp()
            tvStorage?.text = "Workspace: ${settingsManager.getWorkspaceStorageSummary(workspaceManager)}"
            Toast.makeText(this, getString(R.string.settings_clear_cache_success), Toast.LENGTH_SHORT).show()
        }

        // 5. Notification Permission
        val tvNotifications = dialog.findViewById<TextView>(R.id.settings_tv_notification_status)
        val btnNotifications = dialog.findViewById<MaterialButton>(R.id.settings_btn_notification_permission)
        val tvExactAlarms = dialog.findViewById<TextView>(R.id.settings_tv_exact_alarm_status)
        val btnExactAlarms = dialog.findViewById<MaterialButton>(R.id.settings_btn_exact_alarm_permission)

        val refreshNotificationRow = {
            val allowed = notificationsAllowed()
            tvNotifications?.text = getString(
                if (allowed) R.string.settings_notifications_allowed else R.string.settings_notifications_blocked
            )
            tvNotifications?.setTextColor(
                ContextCompat.getColor(this, if (allowed) R.color.accent_green else R.color.accent_red)
            )
            btnNotifications?.text = getString(
                if (!allowed && canPromptForNotifications()) R.string.settings_notifications_allow
                else R.string.settings_notifications_open
            )
        }
        refreshNotificationRow()
        onNotificationPermissionChanged = refreshNotificationRow

        btnNotifications?.setOnClickListener {
            if (!notificationsAllowed() && canPromptForNotifications()) {
                promptNotificationPermission()
            } else {
                storageBridge.openNotificationSettings()
            }
        }

        val refreshExactAlarmRow = {
            val allowed = NativeStorageBridge.canScheduleExact(this)
            tvExactAlarms?.text = getString(
                if (allowed) R.string.settings_exact_alarms_allowed else R.string.settings_exact_alarms_blocked
            )
            tvExactAlarms?.setTextColor(
                ContextCompat.getColor(this, if (allowed) R.color.accent_green else R.color.accent_red)
            )
            btnExactAlarms?.text = getString(
                if (allowed) R.string.settings_exact_alarms_allowed_button else R.string.settings_exact_alarms_allow
            )
            btnExactAlarms?.isEnabled = !allowed
        }
        refreshExactAlarmRow()
        onExactAlarmPermissionChanged = refreshExactAlarmRow
        dialog.setOnDismissListener {
            onNotificationPermissionChanged = null
            onExactAlarmPermissionChanged = null
        }
        btnExactAlarms?.setOnClickListener {
            if (!NativeStorageBridge.canScheduleExact(this) && !storageBridge.requestExactNotificationPermission()) {
                Toast.makeText(this, R.string.settings_exact_alarms_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        // 6. About App² & WebEngine Version Readout
        val tvAppVersion = dialog.findViewById<TextView>(R.id.settings_tv_app_version)
        val tvWebviewVersion = dialog.findViewById<TextView>(R.id.settings_tv_webview_version)
        tvAppVersion?.text = settingsManager.getAppVersion()
        tvWebviewVersion?.text = settingsManager.getWebViewEngineVersion()

        // 7. Danger Zone: Factory Reset App²
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

        // 8. Close Button
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
     * - Wipes WebView cache, form data, history, and web storage
     */
    private fun performFullAppReset() {
        // A replacement operation: the workspace is reserved, running pages are fenced and reload, and the
        // disk work stays off the UI thread. Web-view cleanup follows in finishFullAppReset.
        val settings = settingsManager
        workspaceActions.execute(subapp, "Resetting app…",
            completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.RESET)) { manager, history ->
            check(manager.factoryResetWorkspace()) { "Could not restore the starter app" }
            check(history.clearAllHistory()) { "The app was reset, but its version history could not be cleared" }
            settings.resetSettingsToDefaults()
        }
    }

    private fun finishFullAppReset() {
        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        binding.webView.clearHistory()
        WebStorage.getInstance().deleteOrigin("https://${subapp.id}.app.local")

        logBuffer.clear()
        appendLog("[System] App² factory reset completed. Starter template restored.")

        reloadApp()
        refreshFiles?.invoke()

        Toast.makeText(this, getString(R.string.settings_reset_success), Toast.LENGTH_LONG).show()
    }

    override fun pauseForUpdate() {
        runtimeSession?.suspendForOperation()
    }

    override fun resumeAfterUpdate() {
        runtimeSession?.resumeAfterOperation()
    }

    override fun onDestroy() {
        // Dialog windows belong to this activity, while drafts and operations survive recreation.
        // Detach callbacks before dismissing so teardown cannot clear the next screen's draft.
        listOfNotNull(editorDialog, configDialog, historyDialog).forEach { dialog ->
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
        editorDialog = null; configDialog = null; historyDialog = null
        runtimeSession?.close()
        super.onDestroy()
        if (isFinishing) (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.cancelTask(taskId)
        if (::subapp.isInitialized) SubappSessionRegistry.unregister(subapp.id, this)
        // Release any in-flight file chooser before tearing the WebView down.
        runtimeChooser.complete(null)
        binding.webView.destroy()
    }

    companion object {
        const val EXTRA_SUBAPP_ID = "EXTRA_SUBAPP_ID"
        const val EXTRA_OPEN_SETTINGS = "EXTRA_OPEN_SETTINGS"
        private const val RETURN_TO_FILES = "files"
    }
}
