package com.wynh.appapp

import java.util.Locale

import androidx.core.content.FileProvider

import com.google.android.material.checkbox.MaterialCheckBox

import android.view.LayoutInflater

import android.widget.LinearLayout

import com.wynh.appapp.workspace.WorkspacePackageManager

import android.Manifest
import android.app.Dialog
import android.os.Build
import android.view.ViewGroup
import android.graphics.BitmapFactory
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wynh.appapp.settings.AppSettingsManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import android.widget.PopupWindow
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.shape.ShapeAppearanceModel
import com.wynh.appapp.databinding.ActivitySubappLibraryBinding
import com.wynh.appapp.databinding.ItemSubappBinding
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.appcompat.app.AlertDialog
import com.wynh.appapp.bridge.NativeStorageBridge
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.wynh.appapp.marketplace.CatalogItemState
import com.wynh.appapp.marketplace.DiscoverAdapter
import com.wynh.appapp.marketplace.InstalledProvenance
import com.wynh.appapp.marketplace.MarketplaceMatcher
import com.wynh.appapp.marketplace.MarketplaceUi
import com.wynh.appapp.workspace.LibraryController
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.SubappRepository
import com.wynh.appapp.workspace.WorkspaceActionController
import com.wynh.appapp.workspace.WorkspaceHistoryManager
import com.wynh.appapp.workspace.WorkspaceSnapshot
import com.wynh.appapp.workspace.WorkspaceManager
import com.wynh.appapp.workspace.AppIdentityManager
import com.wynh.appapp.editor.ImageCropManager
import android.graphics.Bitmap
import android.widget.EditText
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.workspace.SubappBackupController
import org.json.JSONObject
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.wynh.appapp.databinding.LibraryHeaderBinding

class SubappLibraryActivity : AppCompatActivity() {
    private lateinit var repository: SubappRepository
    private val library by lazy { ViewModelProvider(this)[com.wynh.appapp.workspace.LibraryController::class.java] }
    private var rowMetadata = emptyMap<String, com.wynh.appapp.workspace.SubappMetadata>()
    private var operationProgress: AlertDialog? = null
    private var libraryRecovery: AlertDialog? = null
    private lateinit var binding: ActivitySubappLibraryBinding
    private val settingsManager by lazy { AppSettingsManager(this) }
    private val updateIndicators by lazy { com.wynh.appapp.updates.SubappUpdateIndicators.of(this) }
    private var guidedUpdates: com.wynh.appapp.updates.GuidedUpdatesController? = null
    private var librarySettingsDialog: Dialog? = null
    private fun refreshUpdateIndicators() {
        if (rowsReady) {
            libraryAdapter.notifyItemRangeChanged(1, displayedRecords.size)
            refreshDiscover()
        }
    }
    internal fun reviewUpdates(records: List<SubappRecord>, downloader: com.wynh.appapp.updates.GitHubUpdateDownloader = com.wynh.appapp.updates.GitHubUpdateDownloader(cacheDir)) {
        guidedUpdates?.stop()
        guidedUpdates = com.wynh.appapp.updates.GuidedUpdatesController(this, records,
            changed = { refreshRows(); refreshUpdateIndicators() }, finished = { guidedUpdates = null }, downloader = downloader)
        guidedUpdates?.start()
    }
    private var refreshSettingsPermissions: (() -> Unit)? = null
    private var notificationPromptRefused = false
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationPromptRefused = !granted
        refreshSettingsPermissions?.invoke()
    }
    private fun notificationsAllowed() = NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun canPromptForNotifications() = Build.VERSION.SDK_INT >= 33 && !notificationPromptRefused
    private fun promptNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private val backups by lazy { ViewModelProvider(this)[SubappBackupController::class.java] }
    private var backupProgress: AlertDialog? = null
    private val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backups.export(uri)
    }
    private val backupRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) MaterialAlertDialogBuilder(this).setTitle(R.string.backup_restore)
            .setMessage(R.string.backup_restore_confirm).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.backup_restore) { _, _ -> backups.restore(uri) }.show()
    }
    private val workspaceActions by lazy { ViewModelProvider(this)[WorkspaceActionController::class.java] }
    private var configDialog: Dialog? = null
    private var backupDialog: Dialog? = null
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

    private var pendingExportId: String? = null
    private val saveZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val id = pendingExportId
        if (uri != null && id != null) library.export(id, null, uri, "app")
        pendingExportId = null
    }

    internal fun showPackageDialog(record: SubappRecord): Dialog {
        val workspaceManager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
        val dialog = Dialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_package)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val filesContainer = dialog.findViewById<LinearLayout>(R.id.package_files_container)
        val nameInput = dialog.findViewById<EditText>(R.id.package_name_input)
        val masterCheckbox = dialog.findViewById<MaterialCheckBox>(R.id.package_master_checkbox)
        val masterCheckboxRow = dialog.findViewById<LinearLayout>(R.id.package_master_checkbox_row)
        val btnShareZip = dialog.findViewById<MaterialButton>(R.id.package_btn_share_zip)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.package_btn_close)
        val filesToggleRow = dialog.findViewById<LinearLayout>(R.id.package_files_toggle_row)
        val filesToggleLabel = dialog.findViewById<TextView>(R.id.package_files_toggle_label)
        val filesChevron = dialog.findViewById<View>(R.id.package_files_chevron)
        val filesSection = dialog.findViewById<LinearLayout>(R.id.package_files_section)

        // Attempt to prefill package name from manifest.json
        val defaultPackageName = try {
            val config = AppIdentityManager(this, record).load()
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
            filesToggleLabel.text = getString(R.string.package_files_toggle, selectedFiles.size, workspaceFiles.size)
        }

        // The individual files stay collapsed by default; the selection is kept either way.
        fun setFilesExpanded(expanded: Boolean) {
            filesSection.visibility = if (expanded) View.VISIBLE else View.GONE
            filesChevron.rotation = if (expanded) 180f else 0f
            ViewCompat.setStateDescription(
                filesToggleRow,
                getString(if (expanded) R.string.package_files_expanded else R.string.package_files_collapsed)
            )
        }
        setFilesExpanded(false)
        filesToggleRow.setOnClickListener { setFilesExpanded(filesSection.visibility != View.VISIBLE) }

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
            if (shouldSelectAll) selectedFiles.addAll(checkboxMap.keys)
            checkboxMap.values.forEach { it.isChecked = shouldSelectAll }
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

            library.export(record.id, selectedFiles.toSet(), null, customPackageName)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        return dialog
    }

    private var rowsReady = false
    private val displayedRecords = mutableListOf<SubappRecord>()
    private lateinit var header: LibraryHeaderBinding
    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var discoverAdapter: DiscoverAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var reorderOriginal = emptyList<SubappRecord>()
    private var dragCancelled = false
    private val stableIds = mutableMapOf<String, Long>()
    private var selectedTabId: Int = R.id.nav_your_apps
    private var currentProvenance = emptyMap<String, InstalledProvenance>()
    private var currentMarketplace = LibraryController.MarketplaceState()
    private var detailSheet: BottomSheetDialog? = null
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importApp(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SubappRepository(this)
        pendingExportId = savedInstanceState?.getString("export_app_id")
        selectedTabId = savedInstanceState?.getInt(STATE_SELECTED_TAB, R.id.nav_your_apps) ?: R.id.nav_your_apps
        val restoringConfig = savedInstanceState?.getString("config_record_id")?.let(repository::resolve)
        pendingConfigIcon = savedInstanceState?.getByteArray("config_icon")
        resetConfigIcon = savedInstanceState?.getBoolean("config_reset_icon") ?: false
        configNameDraft = savedInstanceState?.getString("config_name").orEmpty()
        configDescriptionDraft = savedInstanceState?.getString("config_description").orEmpty()
        hasConfigDraft = restoringConfig != null

        showLibrary()
        restoringConfig?.let(::showConfigure)
        com.wynh.appapp.runtime.WorkspaceOperationUi.bindWithFailures(this, onFailure = { guidedUpdates?.failed(it) == true }) { completion ->
            when (completion.kind) {
                WorkspaceActionController.Kind.UPDATED -> {
                    refreshRows()
                    refreshUpdateIndicators()
                    guidedUpdates?.installed(completion.subject)
                }
                WorkspaceActionController.Kind.SETTINGS_SAVED -> {
                    configDialog?.dismiss()
                    refreshRows()
                    Toast.makeText(this, R.string.config_saved_toast, Toast.LENGTH_SHORT).show()
                }
                WorkspaceActionController.Kind.SNAPSHOT_SAVED -> {
                    Toast.makeText(this, R.string.history_version_saved, Toast.LENGTH_SHORT).show()
                    repository.resolve(completion.subject)?.let(::reopenBackup)
                }
                WorkspaceActionController.Kind.RESTORED -> {
                    refreshRows()
                    backupDialog?.dismiss()
                    Toast.makeText(this, R.string.history_restored, Toast.LENGTH_SHORT).show()
                    repository.resolve(completion.subject)?.let(::reopenBackup)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { library.catalog.collect { value ->
                    if (value.error != null) {
                        if (libraryRecovery == null) libraryRecovery = MaterialAlertDialogBuilder(this@SubappLibraryActivity)
                            .setTitle(R.string.library_recovery_title).setMessage(R.string.library_recovery_message)
                            .setPositiveButton(R.string.library_recover) { _, _ -> library.recover() }
                            .setNegativeButton("Retry") { _, _ -> library.refresh() }
                            .setOnDismissListener { libraryRecovery = null }.show()
                    } else {
                        libraryRecovery?.dismiss(); libraryRecovery = null
                        if (reorderOriginal.isEmpty() && rowsReady) applyCatalog(value)
                    }
                } }
                launch { library.marketplace.collect { state ->
                    currentMarketplace = state
                    state.refreshError?.let { errorMessage ->
                        Toast.makeText(this@SubappLibraryActivity, errorMessage, Toast.LENGTH_LONG).show()
                        library.clearMarketplaceRefreshError()
                    }
                    if (rowsReady) refreshDiscover()
                } }
                launch { library.task.collect { task ->
                    header.newApp.isEnabled = !task.busy
                    if (rowsReady) refreshDiscover()
                    if (task.busy) {
                        val detailText = task.detail.ifEmpty { getString(com.wynh.appapp.runtime.R.string.workspace_working) }
                        if (operationProgress == null) operationProgress = MaterialAlertDialogBuilder(this@SubappLibraryActivity)
                            .setTitle(task.message).setMessage(detailText).setCancelable(false)
                            .setNegativeButton(R.string.cancel) { _, _ -> library.cancel() }.show()
                        operationProgress?.setTitle(task.message)
                        operationProgress?.setMessage(detailText)
                        operationProgress?.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.apply {
                            visibility = if (task.cancellable) View.VISIBLE else View.GONE
                            setOnClickListener { library.cancel() }
                        }
                    } else {
                        operationProgress?.dismiss(); operationProgress = null
                        task.deleted?.let { id ->
                            ShortcutManagerCompat.disableShortcuts(this@SubappLibraryActivity, listOf("appapp_subapp_$id"), "App no longer available")
                            SubappSessionRegistry.close(id)
                        }
                        if (task.message.isNotEmpty()) {
                            if (task.installedFromCatalog != null) {
                                showInstalledSnackbar(task.installedFromCatalog, task.installedName, task.message, task.installedOfflineNote)
                            } else if (task.created != null) {
                                showConfigure(task.created)
                            } else if (task.share != null) {
                                val uri = FileProvider.getUriForFile(this@SubappLibraryActivity, "$packageName.fileprovider", task.share)
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.action_share_zip)))
                            } else Toast.makeText(this@SubappLibraryActivity, task.message, Toast.LENGTH_LONG).show()
                            library.clearTask()
                        }
                    }
                } }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                backups.status.collect { status ->
                    if (status.busy) {
                        if (backupProgress == null) backupProgress = MaterialAlertDialogBuilder(this@SubappLibraryActivity)
                            .setTitle(status.message).setMessage(R.string.backup_progress).setCancelable(false).show()
                    } else {
                        backupProgress?.dismiss(); backupProgress = null
                        if (status.message.isNotEmpty()) {
                            refreshRows()
                            MaterialAlertDialogBuilder(this@SubappLibraryActivity)
                                .setTitle(if (status.error) R.string.backup_failed else R.string.backup_complete)
                                .setMessage(status.message).setPositiveButton(android.R.string.ok, null).show()
                            backups.clearResult()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        configuringRecord?.let { outState.putString("config_record_id", it.id) }
        outState.putByteArray("config_icon", pendingConfigIcon)
        outState.putBoolean("config_reset_icon", resetConfigIcon)
        outState.putString("config_name", configNameInput?.text?.toString() ?: configNameDraft)
        outState.putString("config_description", configDescriptionInput?.text?.toString() ?: configDescriptionDraft)
        outState.putString("export_app_id", pendingExportId)
        outState.putInt(STATE_SELECTED_TAB, selectedTabId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(true)
        if (rowsReady) refreshRows()
        refreshSettingsPermissions?.invoke()
    }

    override fun onDestroy() {
        configDialog?.dismiss()
        backupDialog?.dismiss()
        detailSheet?.dismiss(); detailSheet = null
        librarySettingsDialog?.dismiss(); librarySettingsDialog = null
        guidedUpdates?.stop()
        backupProgress?.dismiss()
        backupProgress = null
        operationProgress?.dismiss(); libraryRecovery?.dismiss()
        super.onDestroy()
    }

    override fun onPause() {
        finishReorder(commit = false)
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(false)
        super.onPause()
    }

    private fun showLibrary() {
        enableEdgeToEdge()
        binding = ActivitySubappLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.libraryRoot) { view, insets ->
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        val initialNavBottom = binding.libraryBottomNav.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.libraryBottomNav) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialNavBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.libraryToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    showLibrarySettings()
                    true
                }
                R.id.action_refresh_catalog -> {
                    library.refreshMarketplace()
                    true
                }
                else -> false
            }
        }
        binding.appList.layoutManager = LinearLayoutManager(this)
        header = LibraryHeaderBinding.inflate(layoutInflater, binding.appList, false)
        libraryAdapter = LibraryAdapter()
        binding.appList.adapter = libraryAdapter
        binding.appList.onDragCancelled = { dragCancelled = true }

        binding.discoverList.layoutManager = LinearLayoutManager(this)
        discoverAdapter = DiscoverAdapter(
            inflater = layoutInflater,
            onCardClick = { state -> showCatalogDetail(state) },
            onPrimaryAction = { state -> handleCatalogPrimaryAction(state) },
            onImportGitHub = { showNewAppMenu(focusGithub = true) },
            onImportZip = { launchZipImport() },
        )
        binding.discoverList.adapter = discoverAdapter

        touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int =
                makeMovementFlags(if (holder is AppHolder && displayedRecords.size > 1) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0, 0)
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = source.bindingAdapterPosition - 1
                val to = target.bindingAdapterPosition - 1
                if (from !in displayedRecords.indices || to !in displayedRecords.indices) return false
                displayedRecords.add(to, displayedRecords.removeAt(from))
                libraryAdapter.notifyItemMoved(from + 1, to + 1)
                return true
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                holder.itemView.translationZ = 0f
            }
            override fun chooseDropTarget(selected: RecyclerView.ViewHolder, targets: MutableList<RecyclerView.ViewHolder>, curX: Int, curY: Int): RecyclerView.ViewHolder? {
                val center = curY + selected.itemView.height / 2f
                val movingDown = curY > selected.itemView.top
                return targets.filter { target ->
                    target is AppHolder && if (movingDown) center > target.itemView.top + target.itemView.height / 2f
                    else center < target.itemView.top + target.itemView.height / 2f
                }.minByOrNull { kotlin.math.abs(center - (it.itemView.top + it.itemView.height / 2f)) }
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    reorderOriginal = displayedRecords.toList()
                    dragCancelled = false
                    holder?.itemView?.translationZ = resources.displayMetrics.density * 12
                    holder?.itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) finishReorder(!dragCancelled)
            }
        })
        touchHelper.attachToRecyclerView(binding.appList)
        header.newApp.setOnClickListener { showNewAppMenu() }
        header.emptyBrowseApps.setOnClickListener { selectTab(R.id.nav_discover) }

        binding.libraryBottomNav.selectedItemId = selectedTabId
        binding.libraryBottomNav.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
        binding.libraryBottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_discover) binding.discoverList.smoothScrollToPosition(0)
            else binding.appList.smoothScrollToPosition(0)
        }

        rowsReady = true
        applySelectedTab()
        refreshDiscover()
        refreshRows()
    }

    private fun selectTab(tabId: Int) {
        if (selectedTabId == tabId) return
        if (tabId != R.id.nav_your_apps) finishReorder(commit = false)
        selectedTabId = tabId
        if (binding.libraryBottomNav.selectedItemId != tabId) {
            binding.libraryBottomNav.selectedItemId = tabId
        }
        applySelectedTab()
    }

    private fun applySelectedTab() {
        val isDiscover = selectedTabId == R.id.nav_discover
        binding.appList.visibility = if (isDiscover) View.GONE else View.VISIBLE
        binding.discoverList.visibility = if (isDiscover) View.VISIBLE else View.GONE
        binding.libraryToolbar.menu.findItem(R.id.action_refresh_catalog)?.isVisible = isDiscover
    }

    private fun refreshDiscover() {
        if (!rowsReady) return
        val states = MarketplaceMatcher.match(
            apps = currentMarketplace.apps,
            records = displayedRecords,
            provenance = currentProvenance,
            updateAvailable = { updateIndicators.available(it) != null },
        )
        discoverAdapter.submit(
            newItems = states,
            icons = currentMarketplace.cachedIcons,
            synced = currentMarketplace.syncedFromRemote,
            isRefreshing = currentMarketplace.refreshing,
            busy = library.task.value.busy,
        )
    }

    private fun handleCatalogPrimaryAction(state: CatalogItemState) {
        val copies = state.installedRecords
        val updateRecord = state.updateRecord
        when {
            copies.size > 1 -> showCopiesInLibrary(copies)
            updateRecord != null -> reviewUpdates(listOf(updateRecord))
            copies.size == 1 -> open(copies.single())
            state.hostCompatible -> library.installCatalogApp(state.app)
        }
    }

    /** Several copies were added manually; each is managed from its own row in Your apps. */
    private fun showCopiesInLibrary(copies: List<SubappRecord>) {
        selectTab(R.id.nav_your_apps)
        val first = displayedRecords.indexOfFirst { record -> copies.any { it.id == record.id } }
        if (first >= 0) binding.appList.scrollToPosition(first + 1)
    }

    private fun recordDisplayName(record: SubappRecord, fallback: String): String =
        rowMetadata[record.id]?.name?.ifBlank { fallback } ?: fallback

    private fun showCatalogDetail(state: CatalogItemState) {
        detailSheet?.dismiss()
        detailSheet = MarketplaceUi.showDetailSheet(
            activity = this,
            state = state,
            cachedIcon = currentMarketplace.cachedIcons[state.app.id],
            taskBusy = library.task.value.busy,
            onInstall = { app -> library.installCatalogApp(app) },
            onOpen = { record -> open(record) },
            onCreateShortcut = { record ->
                if (!AppIdentityManager(this, record).requestInstall()) {
                    Toast.makeText(this, R.string.shortcut_request_failed, Toast.LENGTH_LONG).show()
                }
            },
            onBackup = { record -> showAppBackup(record, recordDisplayName(record, state.app.name)) },
            onConfigure = ::showConfigure,
            onPermissions = { record -> showPermissions(record, recordDisplayName(record, state.app.name)) },
            onDelete = { record -> confirmDelete(record, recordDisplayName(record, state.app.name)) },
            onUpdate = { record -> reviewUpdates(listOf(record)) },
            onShowCopies = { showCopiesInLibrary(state.installedRecords) },
        )
    }

    private fun showInstalledSnackbar(record: SubappRecord, installedName: String?, message: String, offlineNote: Boolean) {
        val text = if (offlineNote && !installedName.isNullOrBlank()) {
            getString(R.string.marketplace_installed_offline_notice, installedName)
        } else {
            message
        }
        Snackbar.make(binding.libraryContent, text, Snackbar.LENGTH_LONG)
            .setAction(R.string.marketplace_action_open) { open(record) }
            .show()
    }

    /** Opens library-wide preferences without creating or selecting a subapp workspace. */
    private fun showLibrarySettings() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_library_settings)
        librarySettingsDialog = dialog
        val updates = com.wynh.appapp.updates.SettingsUpdateController(this, dialog,
            records = { repository.list() }, changed = ::refreshUpdateIndicators, review = { reviewUpdates(it) })
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvNotifications = dialog.findViewById<TextView>(R.id.settings_tv_notification_status)
        val btnNotifications = dialog.findViewById<MaterialButton>(R.id.settings_btn_notification_permission)
        val tvExactAlarms = dialog.findViewById<TextView>(R.id.settings_tv_exact_alarm_status)
        val btnExactAlarms = dialog.findViewById<MaterialButton>(R.id.settings_btn_exact_alarm_permission)
        val refreshNotificationRow = {
            val allowed = notificationsAllowed()
            tvNotifications?.text = getString(if (allowed) R.string.settings_notifications_allowed else R.string.settings_notifications_blocked)
            tvNotifications?.setTextColor(ContextCompat.getColor(this, if (allowed) R.color.accent_green else R.color.accent_red))
            btnNotifications?.text = getString(if (!allowed && canPromptForNotifications()) R.string.settings_notifications_allow else R.string.settings_notifications_open)
        }
        refreshNotificationRow()

        btnNotifications?.setOnClickListener {
            if (!notificationsAllowed() && canPromptForNotifications()) promptNotificationPermission()
            else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val refreshExactAlarmRow = {
            val allowed = NativeStorageBridge.canScheduleExact(this)
            tvExactAlarms?.text = getString(if (allowed) R.string.settings_exact_alarms_allowed else R.string.settings_exact_alarms_blocked)
            tvExactAlarms?.setTextColor(ContextCompat.getColor(this, if (allowed) R.color.accent_green else R.color.accent_red))
            btnExactAlarms?.text = getString(if (allowed) R.string.settings_exact_alarms_allowed_button else R.string.settings_exact_alarms_allow)
            btnExactAlarms?.isEnabled = !allowed
        }
        refreshExactAlarmRow()
        refreshSettingsPermissions = { refreshNotificationRow(); refreshExactAlarmRow() }
        dialog.setOnDismissListener {
            refreshSettingsPermissions = null
            librarySettingsDialog = null
            updates.close()
        }
        btnExactAlarms?.setOnClickListener {
            if (Build.VERSION.SDK_INT < 31) return@setOnClickListener
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:$packageName"))
            if (!NativeStorageBridge.canScheduleExact(this) && intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else if (!NativeStorageBridge.canScheduleExact(this)) {
                Toast.makeText(this, R.string.settings_exact_alarms_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        val close = dialog.findViewById<MaterialButton>(R.id.settings_btn_close)
        close?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.settings_card_about)?.let { card ->
            dialog.findViewById<TextView>(R.id.settings_tv_app_version)?.text = settingsManager.getAppVersion()
            dialog.findViewById<TextView>(R.id.settings_tv_webview_version)?.text = settingsManager.getWebViewEngineVersion()
        }
        dialog.findViewById<MaterialButton>(R.id.settings_btn_privacy_policy)?.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.settings_privacy_policy_url)))) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_privacy_policy_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        val historyButtons = mapOf(
            2 to dialog.findViewById<MaterialButton>(R.id.settings_btn_history_2),
            4 to dialog.findViewById<MaterialButton>(R.id.settings_btn_history_4),
            6 to dialog.findViewById<MaterialButton>(R.id.settings_btn_history_6),
            8 to dialog.findViewById<MaterialButton>(R.id.settings_btn_history_8),
            10 to dialog.findViewById<MaterialButton>(R.id.settings_btn_history_10)
        )
        fun updateHistoryButtons(selected: Int) {
            historyButtons.forEach { (count, button) ->
                button.setBackgroundColor(ContextCompat.getColor(this,
                    if (count == selected) R.color.primary else R.color.surface_elevated))
                button.setTextColor(ContextCompat.getColor(this,
                    if (count == selected) R.color.on_primary else R.color.on_surface_muted))
            }
        }
        updateHistoryButtons(settingsManager.maxHistorySnapshots)
        historyButtons.forEach { (count, button) ->
            button.setOnClickListener {
                settingsManager.maxHistorySnapshots = count
                updateHistoryButtons(count)
                Toast.makeText(this, R.string.settings_history_saved, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.findViewById<View>(R.id.settings_backup_export).setOnClickListener {
            dialog.dismiss()
            backupExport.launch("app2-subapps-${java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date())}.zip")
        }
        dialog.findViewById<View>(R.id.settings_backup_restore).setOnClickListener {
            dialog.dismiss()
            backupRestore.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        dialog.show()
        com.wynh.appapp.ai.AiSettingsController.bind(this, dialog)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun refreshRows() { if (reorderOriginal.isEmpty()) library.refresh() }

    private fun applyCatalog(value: com.wynh.appapp.workspace.LibraryController.Catalog) {
        val previous = displayedRecords.toList()
        val oldMetadata = rowMetadata
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = value.records.size
            override fun areItemsTheSame(old: Int, new: Int) = previous[old].id == value.records[new].id
            override fun areContentsTheSame(old: Int, new: Int): Boolean {
                val id = previous[old].id
                return oldMetadata[id]?.stamp == value.metadata[id]?.stamp
            }
        })
        displayedRecords.clear(); displayedRecords.addAll(value.records); rowMetadata = value.metadata; currentProvenance = value.provenance
        header.appCount.text = resources.getQuantityString(R.plurals.library_count, displayedRecords.size, displayedRecords.size)
        val emptyVis = if (displayedRecords.isEmpty()) View.VISIBLE else View.GONE
        header.emptyState.visibility = emptyVis
        header.emptyBrowseApps.visibility = emptyVis
        diff.dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { libraryAdapter.notifyItemRangeInserted(position + 1, count) }
            override fun onRemoved(position: Int, count: Int) { libraryAdapter.notifyItemRangeRemoved(position + 1, count) }
            override fun onMoved(from: Int, to: Int) { libraryAdapter.notifyItemMoved(from + 1, to + 1) }
            override fun onChanged(position: Int, count: Int, payload: Any?) { libraryAdapter.notifyItemRangeChanged(position + 1, count, payload) }
        })
        // A followed source can change without changing the workspace metadata stamp.
        refreshUpdateIndicators()
    }

    private class AppHolder(val row: ItemSubappBinding) : RecyclerView.ViewHolder(row.root)
    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = displayedRecords.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getItemId(position: Int): Long = if (position == 0) Long.MIN_VALUE else
            stableIds.getOrPut(displayedRecords[position - 1].id) { stableIds.size.toLong() }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            if (type == 0) return object : RecyclerView.ViewHolder(header.root) {}
            val row = ItemSubappBinding.inflate(layoutInflater, parent, false)
            (row.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
                leftMargin = (20 * resources.displayMetrics.density).toInt()
                rightMargin = leftMargin
            }
            val holder = AppHolder(row)
            row.appCard.setOnLongClickListener {
                if (library.task.value.busy || displayedRecords.size < 2 || holder.bindingAdapterPosition == RecyclerView.NO_POSITION) false
                else { touchHelper.startDrag(holder); true }
            }
            ViewCompat.addAccessibilityAction(row.root, getString(R.string.library_move_up)) { _, _ -> moveByAccessibility(holder, -1) }
            ViewCompat.addAccessibilityAction(row.root, getString(R.string.library_move_down)) { _, _ -> moveByAccessibility(holder, 1) }
            return holder
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder !is AppHolder) return
            val record = displayedRecords[position - 1]
            val row = holder.row
            val metadata = rowMetadata[record.id]
            val name = metadata?.name?.ifBlank { getString(com.wynh.appapp.runtime.R.string.library_untitled) } ?: getString(com.wynh.appapp.runtime.R.string.library_untitled)
            val bitmap = metadata?.icon
            row.appIcon.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, resources.displayMetrics.density * 12f)
                .build()
            if (bitmap != null) row.appIcon.setImageBitmap(bitmap)
            else row.appIcon.setImageResource(R.mipmap.ic_launcher_round)
            row.appName.text = name
            row.appDescription.text = metadata?.description?.ifBlank { getString(R.string.library_app_fallback) } ?: getString(R.string.library_app_fallback)
            row.appCard.contentDescription = getString(R.string.library_open_app, name)
            row.appCard.setOnClickListener { open(record) }
            val availableUpdate = updateIndicators.available(record)
            row.updateDot.visibility = if (availableUpdate != null) View.VISIBLE else View.GONE
            row.appMenu.contentDescription = getString(if (availableUpdate != null) R.string.updates_menu_available else R.string.library_menu, name)
            row.appMenu.setOnClickListener { anchor ->
                val content = layoutInflater.inflate(R.layout.popup_library_actions, null)
                val popupWidth = resources.getDimensionPixelSize(R.dimen.library_actions_popup_width)
                // An explicit height lets showAsDropDown flip the menu above the anchor when it would overflow.
                content.measure(
                    View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val popup = PopupWindow(content, popupWidth, content.measuredHeight, true).apply {
                    elevation = resources.getDimension(R.dimen.library_actions_popup_elevation)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    isOutsideTouchable = true
                }
                val marked = updateIndicators.available(record) != null
                if (marked) {
                    val accent = ContextCompat.getColor(this@SubappLibraryActivity, R.color.primary)
                    content.findViewById<View>(R.id.update_app).background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 40)),
                        android.graphics.drawable.ColorDrawable(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 24)), null)
                    content.findViewById<ImageView>(R.id.update_menu_icon).imageTintList = android.content.res.ColorStateList.valueOf(accent)
                    content.findViewById<TextView>(R.id.update_menu_label).apply {
                        setText(R.string.updates_available)
                        setTextColor(accent)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                }
                content.findViewById<View>(R.id.update_app).setOnClickListener {
                    popup.dismiss()
                    if (updateIndicators.available(record) != null) reviewUpdates(listOf(record))
                    else com.wynh.appapp.updates.UpdateController(this@SubappLibraryActivity, record).open()
                }
                content.findViewById<View>(R.id.delete_app).setOnClickListener {
                    popup.dismiss()
                    confirmDelete(record, name)
                }
                content.findViewById<View>(R.id.install_app).setOnClickListener {
                    popup.dismiss()
                    if (!AppIdentityManager(this@SubappLibraryActivity, record).requestInstall())
                        Toast.makeText(this@SubappLibraryActivity, R.string.shortcut_request_failed, Toast.LENGTH_LONG).show()
                }
                content.findViewById<View>(R.id.permissions_app).setOnClickListener { popup.dismiss(); showPermissions(record, name) }
                content.findViewById<View>(R.id.backup_app).setOnClickListener { popup.dismiss(); showAppBackup(record, name) }
                content.findViewById<View>(R.id.configure_app).setOnClickListener { popup.dismiss(); showConfigure(record) }
                popup.showAsDropDown(anchor, anchor.width - popupWidth, 0)
            }
        }
    }

    /** Lets the user grant or revoke each capability of one subapp. */
    private fun showPermissions(record: com.wynh.appapp.workspace.SubappRecord, name: String) {
        val labels = mapOf(
            com.wynh.appapp.grants.SubappCapability.AI to R.string.library_permission_ai,
            com.wynh.appapp.grants.SubappCapability.NOTIFICATIONS to R.string.library_permission_notifications,
            com.wynh.appapp.grants.SubappCapability.NETWORK to R.string.library_permission_network
        ).entries.toList()
        val granted = com.wynh.appapp.grants.SubappGrants.store(this).granted(record.id)
        val checked = BooleanArray(labels.size) { labels[it].key in granted }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.library_permissions_title, name))
            .setMultiChoiceItems(labels.map { getString(it.value) }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton(R.string.library_permissions_done) { _, _ ->
                labels.forEachIndexed { i, e ->
                    com.wynh.appapp.grants.SubappGrants.setGranted(this, record.id, e.key, checked[i])
                }
            }
            .show()
    }

    private fun finishReorder(commit: Boolean) {
        val original = reorderOriginal
        if (original.isEmpty()) return
        reorderOriginal = emptyList()
        if (!commit) {
            displayedRecords.clear()
            displayedRecords.addAll(original)
            binding.appList.post { libraryAdapter.notifyDataSetChanged() }
            return
        }
        if (displayedRecords.map { it.id } == original.map { it.id }) return
        library.reorder(displayedRecords.map { it.id })
    }

    private fun moveByAccessibility(holder: AppHolder, delta: Int): Boolean {
        if (reorderOriginal.isNotEmpty() || library.task.value.busy) return false
        val from = holder.bindingAdapterPosition - 1
        if (from !in displayedRecords.indices) return false
        val to = (from + delta).coerceIn(0, displayedRecords.lastIndex)
        if (from == to) return false
        val item = displayedRecords.removeAt(from)
        displayedRecords.add(to, item)
        library.reorder(displayedRecords.map { it.id })
        libraryAdapter.notifyItemMoved(from + 1, to + 1)
        return true
    }

    private fun open(record: SubappRecord) {
        if (com.wynh.appapp.workspace.WorkspaceOperations.isBusy(record.workspaceDir)) {
            Toast.makeText(this, com.wynh.appapp.runtime.R.string.workspace_working, Toast.LENGTH_SHORT).show(); return
        }
        startActivity(Intent(this, SubappRuntimeActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("${com.wynh.appapp.BuildConfig.APP_LINK_SCHEME}://subapp/${record.id}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        })
    }

    internal fun showAppBackup(record: SubappRecord, name: String) {
        val content = com.wynh.appapp.databinding.DialogAppBackupBinding.inflate(layoutInflater)
        backupDialog?.dismiss()
        val dialog = Dialog(this)
        backupDialog = dialog
        dialog.setOnDismissListener { if (backupDialog === dialog) backupDialog = null }
        dialog.setContentView(content.root)
        content.backupAppName.text = name
        content.backupClose.setOnClickListener { dialog.dismiss() }

        fun addAction(icon: Int, title: Int, open: () -> Unit) {
            val row = com.wynh.appapp.databinding.ItemAppBackupActionBinding.inflate(layoutInflater, content.backupActions, true)
            row.backupActionIcon.setImageResource(icon)
            row.backupActionTitle.setText(title)
            row.root.setOnClickListener {
                dialog.dismiss()
                open()
            }
        }

        addAction(R.drawable.ic_download, R.string.app_backup_export) {
            pendingExportId = record.id
            val filename = WorkspacePackageManager.sanitizePackageName(name)
            saveZipLauncher.launch("$filename.zip")
        }
        addAction(R.drawable.ic_share, R.string.library_share) { showPackageDialog(record) }

        val app = applicationContext
        content.historySaveVersion.setOnClickListener {
            workspaceActions.execute(record, getString(R.string.history_saving_version), replacement = false,
                completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.SNAPSHOT_SAVED, record.id)) { manager, history ->
                check(history.saveSnapshot(manager, app.getString(R.string.history_manual_snapshot)) != null) {
                    app.getString(R.string.history_save_failed)
                }
            }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            minOf((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.density * 460).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        loadBackupVersions(record, dialog, content)
    }

    internal fun showConfigure(record: SubappRecord) {
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
            workspaceActions.execute(record, "Saving app settings…", replacement = false, completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.SETTINGS_SAVED)) { _, _ ->
                val identityManager = AppIdentityManager(app, record)
                check(identityManager.save(newName, newDescription, iconChange)) { "Could not save app settings" }
                identityManager.refreshPinnedShortcut()
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

    private fun reopenBackup(record: SubappRecord) {
        val name = AppIdentityManager(this, record).load().name
        showAppBackup(record, name)
    }

    private fun loadBackupVersions(
        record: SubappRecord,
        dialog: Dialog,
        content: com.wynh.appapp.databinding.DialogAppBackupBinding,
    ) {
        lifecycleScope.launch {
            val snapshots = try {
                withContext(Dispatchers.IO) {
                    WorkspaceHistoryManager(applicationContext, appDirectory = record.directory).getSnapshots()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                content.historyEmptyState.setText(R.string.history_load_failed)
                return@launch
            }
            if (backupDialog !== dialog) return@launch
            content.historyEmptyState.setText(R.string.history_empty)
            content.historyEmptyState.visibility = if (snapshots.isEmpty()) View.VISIBLE else View.GONE
            content.backupVersionScroll.visibility = if (snapshots.isEmpty()) View.GONE else View.VISIBLE
            for (snapshot in snapshots) {
                val row = com.wynh.appapp.databinding.ItemHistorySnapshotBinding.inflate(
                    layoutInflater, content.historySnapshotsContainer, true
                )
                val isInitial = snapshot.id == WorkspaceHistoryManager.INITIAL_SNAPSHOT_ID
                row.historyItemTime.text = if (isInitial) getString(R.string.history_initial_version) else snapshot.formattedTime
                row.historyItemDetails.text = snapshot.label
                row.historyItemDetails.visibility = if (isInitial) View.GONE else View.VISIBLE
                row.historyBtnRestore.setOnClickListener { confirmVersionRestore(record, snapshot) }
            }
        }
    }

    private fun confirmVersionRestore(record: SubappRecord, snapshot: WorkspaceSnapshot) {
        val content = com.wynh.appapp.databinding.DialogRestoreAppVersionBinding.inflate(layoutInflater)
        content.restoreVersionDetails.text = getString(R.string.history_restore_details, snapshot.formattedTime, snapshot.label)
        val app = applicationContext
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_restore_title)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.history_restore) { _, _ ->
                val includePersonalFiles = content.restorePersonalFiles.isChecked
                workspaceActions.execute(record, getString(R.string.history_restoring),
                    completion = WorkspaceActionController.Completion(WorkspaceActionController.Kind.RESTORED, subject = record.id)) { manager, history ->
                    check(history.restoreSnapshot(snapshot.id, manager, includePersonalFiles)) {
                        app.getString(R.string.history_restore_failed)
                    }
                    AppIdentityManager(app, record).refreshPinnedShortcut()
                }
            }
            .show()
    }

    private fun confirmDelete(record: SubappRecord, name: String) {
        AlertDialog.Builder(this).setTitle("Delete $name?")
            .setMessage("Its files, data, and history will be removed. App² will disable its shortcut.")
            // Shortcut, open screens and reminders are only touched once the delete has succeeded.
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> library.delete(record.id) }.show()
    }

    private fun importApp(uri: Uri) { library.importZip(uri) }

    private fun launchZipImport() {
        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    }

    private fun showNewAppMenu(focusGithub: Boolean = false) {
        val content = com.wynh.appapp.databinding.DialogLibraryImportBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(content.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        content.importClose.setOnClickListener { dialog.dismiss() }
        content.importZip.setOnClickListener {
            dialog.dismiss()
            launchZipImport()
        }
        content.createStarter.setOnClickListener {
            dialog.dismiss()
            library.create()
        }
        content.importGithub.setOnClickListener {
            val url = content.githubUrl.text.toString().trim()
            if (url.isBlank()) {
                content.githubUrlLayout.error = getString(R.string.library_github_required)
            } else {
                dialog.dismiss()
                importFromGitHub(url)
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            minOf((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.density * 480).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (focusGithub) content.githubUrl.requestFocus()
    }

    private fun importFromGitHub(url: String) { library.importGitHub(url) }

    companion object {
        private const val STATE_SELECTED_TAB = "library_selected_tab"
    }
}
