package com.wynh.appapp


import androidx.core.content.FileProvider


import android.view.LayoutInflater

import android.widget.LinearLayout

import com.wynh.appapp.workspace.WorkspacePackageManager

import android.Manifest
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.wynh.appapp.workspace.WorkspaceManager
import com.wynh.appapp.workspace.GitHubFolderImporter
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
import androidx.viewpager2.widget.ViewPager2
import android.content.ActivityNotFoundException
import androidx.core.net.toUri
import com.wynh.appapp.updates.GitHubSource
import com.wynh.appapp.updates.SubappUpdateIndicators
import com.wynh.appapp.updates.VersionUpdateChecker
import com.wynh.appapp.updates.WorkspaceUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
    private var backupRecordId: String? = null
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
        if (uri != null && id != null) library.export(id, uri, "app")
        pendingExportId = null
    }

    internal fun showPackageDialog(record: SubappRecord): Dialog {
        val dialog = Dialog(this)
        val content = com.wynh.appapp.databinding.DialogPackageBinding.inflate(layoutInflater)
        dialog.setContentView(content.root)
        val name = AppIdentityManager(this, record).load().name
        content.packageAppName.text = name
        content.packageNameInput.setText(WorkspacePackageManager.sanitizePackageName(name.lowercase(), "my_app"))

        val choices = mapOf(
            R.id.package_both_files to (LibraryController.ExportFiles.BOTH to R.string.package_both_summary),
            R.id.package_app_files to (LibraryController.ExportFiles.APP to R.string.package_app_summary),
            R.id.package_user_files to (LibraryController.ExportFiles.USER to R.string.package_user_summary),
        )
        content.packageFileSelection.addOnButtonCheckedListener { _, id, checked ->
            if (checked) content.packageSelectionSummary.setText(choices.getValue(id).second)
        }
        content.packageBtnShareZip.setOnClickListener {
            if (library.task.value.busy) return@setOnClickListener
            val selected = choices.getValue(content.packageFileSelection.checkedButtonId).first
            val filename = WorkspacePackageManager.sanitizePackageName(content.packageNameInput.text.toString(), "my_app")
            dialog.dismiss()
            library.export(record.id, null, filename, selected)
        }
        content.packageBtnClose.setOnClickListener { dialog.dismiss() }
        content.packageUserFiles.isEnabled = false
        lifecycleScope.launch {
            val hasUserFiles = withContext(Dispatchers.IO) {
                runCatching {
                    val manager = WorkspaceManager(applicationContext, workspaceDir = record.workspaceDir)
                    synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(record.workspaceDir)) {
                        val appFiles = manager.appFilePaths(record.directory)
                        manager.listFiles().any { it.name !in appFiles }
                    }
                }.getOrDefault(false)
            }
            content.packageUserFiles.isEnabled = hasUserFiles
            if (!hasUserFiles && content.packageFileSelection.checkedButtonId == R.id.package_both_files) {
                content.packageSelectionSummary.setText(R.string.package_no_user_files)
            }
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        sizeLibraryDialog(dialog, 460)
        return dialog
    }

    private var rowsReady = false
    private val displayedRecords = mutableListOf<SubappRecord>()
    private lateinit var appList: LibraryRecyclerView
    private lateinit var discoverList: RecyclerView
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
    private var updateCheckJob: Job? = null
    private var updateCheckBusy = false
    private var hostUpdateRelease: VersionUpdateChecker.Release? = null
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
        savedInstanceState?.getString("backup_app_id")?.let(repository::resolve)?.let(::reopenBackup)
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
                    binding.libraryToolbar.menu.findItem(R.id.action_import).actionView?.isEnabled = !task.busy
                    header.emptyImportApp.isEnabled = !task.busy
                    if (rowsReady) refreshDiscover()
                    if (task.busy && !task.showProgress) return@collect
                    if (task.busy) {
                        val detailText = task.detail.ifEmpty { getString(com.wynh.appapp.runtime.R.string.workspace_working) }
                        if (operationProgress == null) operationProgress = MaterialAlertDialogBuilder(this@SubappLibraryActivity)
                            .setTitle(task.message).setView(progressBody(detailText)).setCancelable(false)
                            .setNegativeButton(R.string.cancel) { _, _ -> library.cancel() }.show()
                        operationProgress?.setTitle(task.message)
                        operationProgress?.findViewById<TextView>(com.wynh.appapp.runtime.R.id.workspace_progress_detail)?.text = detailText
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
                            } else if (task.imported != null) {
                                selectTab(R.id.nav_your_apps)
                                showInstalledSnackbar(task.imported, null, task.message, false)
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
                            .setTitle(status.message).setView(progressBody(getString(R.string.backup_progress))).setCancelable(false).show()
                    } else {
                        backupProgress?.dismiss(); backupProgress = null
                        if (status.message.isNotEmpty()) {
                            refreshRows()
                            refreshSettingsPermissions?.invoke()
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
        outState.putString("backup_app_id", backupRecordId)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_YOUR_APPS, false)) selectTab(R.id.nav_your_apps)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        configDialog?.let { sizeLibraryDialog(it, 520) }
        backupDialog?.let { sizeLibraryDialog(it, 460) }
    }

    private fun sizeLibraryDialog(dialog: Dialog, maximumWidthDp: Int) {
        val metrics = resources.displayMetrics
        dialog.window?.setLayout(
            minOf((metrics.widthPixels * 0.94).toInt(), (metrics.density * maximumWidthDp).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
        val toolbarImport = binding.libraryToolbar.menu.findItem(R.id.action_import).actionView
        toolbarImport?.setOnClickListener { showNewAppMenu() }
        binding.libraryToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    showLibrarySettings()
                    true
                }
                R.id.action_refresh_catalog -> {
                    refreshAll()
                    true
                }
                else -> false
            }
        }
        binding.hostUpdateBannerAction.setOnClickListener {
            val url = hostUpdateRelease?.url ?: return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.updates_browser_missing, Toast.LENGTH_SHORT).show()
            }
        }
        binding.hostUpdateBannerDismiss.setOnClickListener {
            hostUpdateRelease = null
            binding.hostUpdateBanner.visibility = View.GONE
        }

        appList = layoutInflater.inflate(R.layout.page_library_apps, binding.libraryPager, false) as LibraryRecyclerView
        discoverList = layoutInflater.inflate(R.layout.page_library_discover, binding.libraryPager, false) as RecyclerView
        appList.layoutManager = LinearLayoutManager(this)
        header = LibraryHeaderBinding.inflate(layoutInflater, appList, false)
        libraryAdapter = LibraryAdapter()
        appList.adapter = libraryAdapter
        appList.onDragCancelled = { dragCancelled = true }

        discoverList.layoutManager = LinearLayoutManager(this)
        discoverAdapter = DiscoverAdapter(
            inflater = layoutInflater,
            onCardClick = { state -> showCatalogDetail(state) },
            onPrimaryAction = { state -> handleCatalogPrimaryAction(state) },
        )
        discoverList.adapter = discoverAdapter

        binding.libraryPager.offscreenPageLimit = 1
        binding.libraryPager.adapter = LibraryPagerAdapter(listOf(appList, discoverList))
        binding.libraryPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val tabId = if (position == 1) R.id.nav_discover else R.id.nav_your_apps
                if (tabId != R.id.nav_your_apps) finishReorder(commit = false)
                selectedTabId = tabId
                if (binding.libraryBottomNav.selectedItemId != tabId) {
                    binding.libraryBottomNav.selectedItemId = tabId
                }
            }
        })

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
                (holder as? AppHolder)?.row?.appCard?.strokeWidth = 0
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
                    binding.libraryPager.isUserInputEnabled = false
                    reorderOriginal = displayedRecords.toList()
                    dragCancelled = false
                    holder?.itemView?.translationZ = resources.displayMetrics.density * 12
                    (holder as? AppHolder)?.row?.appCard?.apply {
                        strokeColor = ContextCompat.getColor(this@SubappLibraryActivity, R.color.primary)
                        strokeWidth = (2 * resources.displayMetrics.density).toInt()
                    }
                    holder?.itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    binding.libraryPager.isUserInputEnabled = true
                    finishReorder(!dragCancelled)
                }
            }
        })
        touchHelper.attachToRecyclerView(appList)
        header.emptyImportApp.setOnClickListener { showNewAppMenu() }
        header.emptyBrowseApps.setOnClickListener { selectTab(R.id.nav_discover) }

        binding.libraryBottomNav.selectedItemId = selectedTabId
        binding.libraryBottomNav.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
        binding.libraryBottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_discover) discoverList.smoothScrollToPosition(0)
            else appList.smoothScrollToPosition(0)
        }

        rowsReady = true
        applySelectedTab(smooth = false)
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
        applySelectedTab(smooth = true)
    }

    private fun applySelectedTab(smooth: Boolean = false) {
        val target = if (selectedTabId == R.id.nav_discover) 1 else 0
        if (binding.libraryPager.currentItem != target) {
            binding.libraryPager.setCurrentItem(target, smooth)
        }
        updateCatalogRefreshIndicator()
    }

    private fun updateCatalogRefreshIndicator() {
        val refresh = binding.libraryToolbar.menu.findItem(R.id.action_refresh_catalog) ?: return
        val refreshing = currentMarketplace.refreshing || updateCheckBusy
        refresh.isEnabled = !refreshing
        if (refreshing && refresh.actionView == null) {
            refresh.actionView = layoutInflater.inflate(R.layout.action_catalog_refresh, binding.libraryToolbar, false)
        } else if (!refreshing) {
            refresh.actionView = null
        }
    }

    /** Refreshes Discover and silently checks App² + installed subapps for updates. */
    private fun refreshAll() {
        library.refreshMarketplace()
        if (updateCheckJob?.isActive == true) return
        updateCheckBusy = true
        updateCatalogRefreshIndicator()
        val checker = VersionUpdateChecker()
        val indicators = SubappUpdateIndicators.of(this)
        updateCheckJob = lifecycleScope.launch {
            var hostNewer = false
            var hostFailed = false
            val found = mutableListOf<SubappRecord>()
            var failures = 0
            var apps = emptyList<SubappRecord>()
            try {
                try {
                    val latest = withContext(Dispatchers.IO) { checker.hostRelease(BuildConfig.BUILD_TYPE == "dev") }
                    hostNewer = latest?.isNewer(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_TYPE == "dev") == true
                    if (hostNewer && latest != null) {
                        hostUpdateRelease = latest
                        binding.hostUpdateBannerText.text = getString(R.string.updates_banner_host, latest.version)
                        binding.hostUpdateBanner.visibility = View.VISIBLE
                    } else {
                        hostUpdateRelease = null
                        binding.hostUpdateBanner.visibility = View.GONE
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { hostFailed = true }

                apps = withContext(Dispatchers.IO) { repository.list() }
                val lookups = mutableMapOf<GitHubSource, Result<VersionUpdateChecker.PublishedVersion>>()
                for (record in apps) {
                    ensureActive()
                    try {
                        val available = withContext(Dispatchers.IO) {
                            val updater = WorkspaceUpdater(record.directory)
                            val source = updater.installed()?.source
                            if (source == null) null else {
                                val published = lookups.getOrPut(source) { runCatching { checker.subapp(source) } }.getOrThrow()
                                if (VersionUpdateChecker.newer(published.version, updater.currentVersion())) published else null
                            }
                        }
                        ensureActive()
                        if (available == null) {
                            indicators.clear(record.id)
                        } else {
                            val stillNewer = withContext(Dispatchers.IO) {
                                val updater = WorkspaceUpdater(record.directory)
                                updater.installed()?.source == available.source &&
                                    VersionUpdateChecker.newer(available.version, updater.currentVersion())
                            }
                            ensureActive()
                            if (stillNewer) {
                                indicators.record(record.id, available)
                                found.add(record)
                            } else {
                                indicators.clear(record.id)
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failures++ }
                    refreshUpdateIndicators()
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                updateCheckBusy = false
                updateCatalogRefreshIndicator()
            }

            when {
                found.isNotEmpty() -> {
                    val text = resources.getQuantityString(R.plurals.updates_subapps_found, found.size, found.size)
                    Snackbar.make(binding.libraryContent, text, Snackbar.LENGTH_LONG)
                        .setAction(R.string.updates_snackbar_review) { reviewUpdates(found) }
                        .show()
                }
                hostNewer -> Unit
                hostFailed && failures == apps.size -> {
                    Snackbar.make(binding.libraryContent, R.string.updates_snackbar_failed, Snackbar.LENGTH_SHORT).show()
                }
                hostFailed || failures > 0 -> {
                    Snackbar.make(binding.libraryContent, R.string.updates_snackbar_partial_failed, Snackbar.LENGTH_SHORT).show()
                }
                else -> {
                    Snackbar.make(binding.libraryContent, R.string.updates_snackbar_up_to_date, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private class LibraryPagerAdapter(private val pages: List<View>) : RecyclerView.Adapter<LibraryPagerAdapter.PageHolder>() {
        class PageHolder(view: View) : RecyclerView.ViewHolder(view)
        override fun getItemCount() = pages.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val page = pages[viewType]
            (page.parent as? ViewGroup)?.removeView(page)
            page.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return PageHolder(page)
        }
        override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit
    }

    private fun refreshDiscover() {
        if (!rowsReady) return
        updateCatalogRefreshIndicator()
        val states = MarketplaceMatcher.match(
            apps = currentMarketplace.apps,
            records = displayedRecords,
            provenance = currentProvenance,
            updateAvailable = { updateIndicators.available(it) != null },
        )
        discoverAdapter.submit(
            newItems = states,
            icons = currentMarketplace.cachedIcons,
            syncedAtMillis = currentMarketplace.syncedAtMillis,
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
            else -> showUpdates()
        }
    }

    /** Several copies were added manually; each is managed from its own row in Your apps. */
    private fun showCopiesInLibrary(copies: List<SubappRecord>) {
        selectTab(R.id.nav_your_apps)
        val first = displayedRecords.indexOfFirst { record -> copies.any { it.id == record.id } }
        if (first < 0) return
        val copyIds = copies.map { it.id }.toSet()
        appList.scrollToPosition(first + 1)
        appList.post {
            for (index in 0 until appList.childCount) {
                val holder = appList.getChildViewHolder(appList.getChildAt(index)) as? AppHolder ?: continue
                val record = displayedRecords.getOrNull(holder.bindingAdapterPosition - 1) ?: continue
                if (record.id !in copyIds) continue
                val card = holder.row.appCard
                card.strokeColor = ContextCompat.getColor(this, R.color.primary)
                card.strokeWidth = (2 * resources.displayMetrics.density).toInt()
                card.postDelayed({ if (reorderOriginal.isEmpty()) card.strokeWidth = 0 }, 1300)
            }
        }
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
            onCheckHostUpdate = { showUpdates() },
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

    private fun showUpdates() {
        val content = layoutInflater.inflate(R.layout.dialog_update_check, null)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.updates_check)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .show()
        val controller = com.wynh.appapp.updates.SettingsUpdateController(
            this, dialog, records = { repository.list() }, changed = ::refreshUpdateIndicators,
            review = { librarySettingsDialog?.dismiss(); reviewUpdates(it) }
        )
        dialog.setOnDismissListener { controller.close() }
        controller.start()
    }

    /** Opens library-wide preferences without creating or selecting a subapp workspace. */
    private fun showLibrarySettings() {
        val dialog = Dialog(this, R.style.Theme_AppApp_FullScreenDialog)
        dialog.setContentView(R.layout.dialog_library_settings)
        librarySettingsDialog = dialog
        dialog.findViewById<View>(R.id.settings_check_host).setOnClickListener { showUpdates() }

        val notificationsRow = dialog.findViewById<View>(R.id.settings_notifications_row)
        val tvNotifications = dialog.findViewById<TextView>(R.id.settings_tv_notification_status)
        val notificationsChevron = dialog.findViewById<View>(R.id.settings_notifications_chevron)
        val notificationsActions = dialog.findViewById<View>(R.id.settings_notifications_actions)
        val btnNotifications = dialog.findViewById<MaterialButton>(R.id.settings_btn_notification_permission)
        val tvExactAlarms = dialog.findViewById<TextView>(R.id.settings_tv_exact_alarm_status)
        val btnExactAlarms = dialog.findViewById<MaterialButton>(R.id.settings_btn_exact_alarm_permission)
        val backupSummary = dialog.findViewById<TextView>(R.id.settings_backup_summary)

        val openAppNotificationSettings = {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
        val refreshNotificationsSection = {
            val notifAllowed = notificationsAllowed()
            val exactAllowed = NativeStorageBridge.canScheduleExact(this)
            val allAllowed = notifAllowed && exactAllowed
            notificationsChevron?.visibility = if (allAllowed) View.VISIBLE else View.GONE
            notificationsActions?.visibility = if (allAllowed) View.GONE else View.VISIBLE
            notificationsRow?.isClickable = allAllowed
            notificationsRow?.isFocusable = allAllowed
            notificationsRow?.setOnClickListener(if (allAllowed) View.OnClickListener { openAppNotificationSettings() } else null)
            tvNotifications?.text = getString(when {
                allAllowed -> R.string.settings_notifications_all_allowed
                !notifAllowed -> R.string.settings_notifications_blocked
                else -> R.string.settings_exact_alarms_blocked
            })
            tvNotifications?.setTextColor(ContextCompat.getColor(this, if (allAllowed) R.color.accent_green else R.color.accent_red))
            btnNotifications?.visibility = if (notifAllowed) View.GONE else View.VISIBLE
            btnNotifications?.text = getString(if (canPromptForNotifications()) R.string.settings_notifications_allow else R.string.settings_notifications_open)
            tvExactAlarms?.visibility = if (!notifAllowed && !exactAllowed) View.VISIBLE else View.GONE
            btnExactAlarms?.visibility = if (exactAllowed) View.GONE else View.VISIBLE
            btnExactAlarms?.text = getString(R.string.settings_exact_alarms_allow)
        }
        val refreshBackupSummary = {
            val lastBackup = settingsManager.lastBackupAtMillis
            backupSummary?.text = if (lastBackup > 0L) {
                getString(
                    R.string.backup_last_backed_up,
                    android.text.format.DateUtils.getRelativeTimeSpanString(
                        lastBackup,
                        System.currentTimeMillis(),
                        android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    )
                )
            } else {
                getString(R.string.backup_short_summary)
            }
        }
        refreshNotificationsSection()
        refreshBackupSummary()

        btnNotifications?.setOnClickListener {
            if (!notificationsAllowed() && canPromptForNotifications()) promptNotificationPermission()
            else openAppNotificationSettings()
        }
        refreshSettingsPermissions = { refreshNotificationsSection(); refreshBackupSummary() }
        var aiSettingsJob: kotlinx.coroutines.Job? = null
        dialog.setOnDismissListener {
            aiSettingsJob?.cancel()
            refreshSettingsPermissions = null
            librarySettingsDialog = null
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
        val close = dialog.findViewById<View>(R.id.settings_btn_close)
        close?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.settings_card_about)?.let { card ->
            dialog.findViewById<TextView>(R.id.settings_tv_app_version)?.text = settingsManager.getAppVersion()
            dialog.findViewById<TextView>(R.id.settings_tv_webview_version)?.text = settingsManager.getWebViewEngineVersion()
        }
        dialog.findViewById<View>(R.id.settings_btn_privacy_policy)?.setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.settings_privacy_policy_url)))) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.settings_privacy_policy_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        val historyLimits = listOf(2, 4, 6, 8, 10)
        val historySummary = dialog.findViewById<TextView>(R.id.settings_history_summary)
        fun showHistoryLimit(count: Int) {
            historySummary.text = resources.getQuantityString(R.plurals.settings_history_summary, count, count)
        }
        showHistoryLimit(settingsManager.maxHistorySnapshots)
        dialog.findViewById<View>(R.id.settings_history_row).setOnClickListener {
            val options = historyLimits.map { resources.getQuantityString(R.plurals.settings_history_option, it, it) }
            val explanation = layoutInflater.inflate(R.layout.dialog_history_limit_note, null)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_history_picker_title)
                .setSingleChoiceItems(options.toTypedArray(), historyLimits.indexOf(settingsManager.maxHistorySnapshots)) { picker, index ->
                    val count = historyLimits[index]
                    settingsManager.maxHistorySnapshots = count
                    showHistoryLimit(count)
                    picker.dismiss()
                }
                .setView(explanation)
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        dialog.findViewById<View>(R.id.settings_backup_export).setOnClickListener {
            backupExport.launch("app2-subapps-${java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date())}.zip")
        }
        dialog.findViewById<View>(R.id.settings_backup_restore).setOnClickListener {
            backupRestore.launch(arrayOf("application/zip", "application/x-zip-compressed"))
        }
        dialog.show()
        dialog.window?.apply {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val settingsRoot = dialog.findViewById<View>(R.id.settings_root)
        val margin = settingsRoot.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(settingsRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(margin + bars.left, margin + bars.top, margin + bars.right, margin + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(settingsRoot)
        aiSettingsJob = com.wynh.appapp.ai.AiSettingsController.bind(this, dialog)
    }

    private fun progressBody(detail: String): View =
        layoutInflater.inflate(com.wynh.appapp.runtime.R.layout.dialog_workspace_progress, null).apply {
            findViewById<TextView>(com.wynh.appapp.runtime.R.id.workspace_progress_detail).text = detail
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
        val hadFooter = previous.size >= 2
        val hasFooter = value.records.size >= 2
        displayedRecords.clear(); displayedRecords.addAll(value.records); rowMetadata = value.metadata; currentProvenance = value.provenance
        header.appCount.text = resources.getQuantityString(R.plurals.library_count, displayedRecords.size, displayedRecords.size)
        val emptyVis = if (displayedRecords.isEmpty()) View.VISIBLE else View.GONE
        header.emptyTitle.visibility = emptyVis
        header.emptyState.visibility = emptyVis
        header.emptyBrowseApps.visibility = emptyVis
        header.emptyImportApp.visibility = emptyVis
        header.appCountRow.visibility = if (displayedRecords.isEmpty()) View.GONE else View.VISIBLE
        header.appCount.visibility = header.appCountRow.visibility
        diff.dispatchUpdatesTo(object : androidx.recyclerview.widget.ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) { libraryAdapter.notifyItemRangeInserted(position + 1, count) }
            override fun onRemoved(position: Int, count: Int) { libraryAdapter.notifyItemRangeRemoved(position + 1, count) }
            override fun onMoved(from: Int, to: Int) { libraryAdapter.notifyItemMoved(from + 1, to + 1) }
            override fun onChanged(position: Int, count: Int, payload: Any?) { libraryAdapter.notifyItemRangeChanged(position + 1, count, payload) }
        })
        if (!hadFooter && hasFooter) libraryAdapter.notifyItemInserted(displayedRecords.size + 1)
        else if (hadFooter && !hasFooter) libraryAdapter.notifyItemRemoved(displayedRecords.size + 1)
        // A followed source can change without changing the workspace metadata stamp.
        refreshUpdateIndicators()
    }

    private class AppHolder(val row: ItemSubappBinding) : RecyclerView.ViewHolder(row.root)
    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        private val showFooter get() = displayedRecords.size >= 2
        override fun getItemCount() = displayedRecords.size + 1 + if (showFooter) 1 else 0
        override fun getItemViewType(position: Int) = when {
            position == 0 -> 0
            position == displayedRecords.size + 1 -> 2
            else -> 1
        }
        override fun getItemId(position: Int): Long = when {
            position == 0 -> Long.MIN_VALUE
            position == displayedRecords.size + 1 -> Long.MIN_VALUE + 1
            else -> stableIds.getOrPut(displayedRecords[position - 1].id) { stableIds.size.toLong() }
        }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            if (type == 0) return object : RecyclerView.ViewHolder(header.root) {}
            if (type == 2) return object : RecyclerView.ViewHolder(layoutInflater.inflate(R.layout.library_footer, parent, false)) {}
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
            row.appCard.strokeWidth = 0
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
            row.appUpdate.visibility = if (availableUpdate != null) View.VISIBLE else View.GONE
            row.appUpdate.setOnClickListener { reviewUpdates(listOf(record)) }
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
                popup.showAsDropDown(anchor, 0, 0, android.view.Gravity.END)
            }
        }
    }

    /** Lets the user grant or revoke each capability of one subapp. */
    private fun showPermissions(record: com.wynh.appapp.workspace.SubappRecord, name: String) {
        val aiReady = (application as? com.wynh.appapp.ai.AppAiApplication)
            ?.ai?.registry?.providers?.any { it.connected() } == true
        val permissions = listOf(
            Triple(com.wynh.appapp.grants.SubappCapability.NETWORK, R.string.library_permission_internet_title,
                R.string.library_permission_internet_summary),
            Triple(com.wynh.appapp.grants.SubappCapability.AI, R.string.library_permission_ai_title,
                if (aiReady) R.string.library_permission_ai_summary else R.string.library_permission_ai_setup),
            Triple(com.wynh.appapp.grants.SubappCapability.NOTIFICATIONS, R.string.library_permission_notifications_title,
                if (notificationsAllowed()) R.string.library_permission_notifications_summary else R.string.library_permission_notifications_setup),
        )
        val granted = com.wynh.appapp.grants.SubappGrants.store(this).granted(record.id)
        val content = layoutInflater.inflate(R.layout.dialog_list_content, null)
        val rows = content.findViewById<android.widget.LinearLayout>(R.id.dialog_list)
        val switches = permissions.associate { (capability, title, summary) ->
            val row = com.wynh.appapp.databinding.ItemPermissionBinding.inflate(layoutInflater, rows, true)
            row.permissionSwitch.setText(title)
            row.permissionSwitch.isChecked = capability in granted
            row.permissionSummary.setText(summary)
            capability to row.permissionSwitch
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.library_permissions_title, name))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.library_permissions_done) { _, _ ->
                switches.forEach { (capability, control) ->
                    com.wynh.appapp.grants.SubappGrants.setGranted(this, record.id, capability, control.isChecked)
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
            appList.post { libraryAdapter.notifyDataSetChanged() }
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

    private var backupContent: com.wynh.appapp.databinding.DialogAppBackupBinding? = null

    internal fun showAppBackup(record: SubappRecord, name: String) {
        val content = com.wynh.appapp.databinding.DialogAppBackupBinding.inflate(layoutInflater)
        backupDialog?.dismiss()
        val dialog = Dialog(this)
        backupDialog = dialog
        backupContent = content
        backupRecordId = record.id
        dialog.setOnDismissListener {
            if (backupDialog === dialog) {
                backupDialog = null
                backupContent = null
                backupRecordId = null
            }
        }
        dialog.setContentView(content.root)
        content.backupAppName.text = name
        content.backupClose.setOnClickListener { dialog.dismiss() }

        fun addAction(icon: Int, title: Int, summary: Int? = null, open: () -> Unit) {
            val row = com.wynh.appapp.databinding.ItemAppBackupActionBinding.inflate(layoutInflater, content.backupActions, true)
            row.backupActionIcon.setImageResource(icon)
            row.backupActionTitle.setText(title)
            summary?.let { row.backupActionSummary.setText(it); row.backupActionSummary.visibility = View.VISIBLE }
            row.root.setOnClickListener { open() }
        }

        addAction(R.drawable.ic_download_device, R.string.app_backup_export, R.string.app_backup_export_summary) {
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
        sizeLibraryDialog(dialog, 460)
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
        refreshConfigIconPreview(record)
        dialog.show()
        sizeLibraryDialog(dialog, 520)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun refreshConfigIconPreview(record: SubappRecord) {
        val preview = configIconPreview ?: return
        val bitmap = when {
            resetConfigIcon -> null
            pendingConfigIcon != null -> BitmapFactory.decodeByteArray(pendingConfigIcon, 0, pendingConfigIcon!!.size)
            else -> com.wynh.appapp.workspace.SubappMetadataLoader.load(record.workspaceDir).icon
        }
        configDialog?.findViewById<MaterialButton>(R.id.config_btn_reset_icon)?.isEnabled = bitmap != null
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
        val dialog = backupDialog
        val content = backupContent
        if (dialog != null && content != null && backupRecordId == record.id) {
            loadBackupVersions(record, dialog, content)
        } else {
            showAppBackup(record, AppIdentityManager(this, record).load().name)
        }
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
            content.historySnapshotsContainer.removeAllViews()
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
        content.restoreVersionDetails.text = if (snapshot.id == WorkspaceHistoryManager.INITIAL_SNAPSHOT_ID)
            getString(R.string.history_initial_version)
        else getString(R.string.history_restore_details, snapshot.formattedTime, snapshot.label)
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
        MaterialAlertDialogBuilder(this).setTitle("Delete $name?")
            .setMessage("Its files, data, and history will be removed. App² will disable its shortcut.")
            // Shortcut, open screens and reminders are only touched once the delete has succeeded.
            .setNegativeButton("Cancel", null).setPositiveButton(R.string.library_delete) { _, _ -> library.delete(record.id) }.show()
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.error))
    }

    private fun importApp(uri: Uri) { library.importZip(uri) }

    private fun launchZipImport() {
        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    }

    private fun showNewAppMenu() {
        if (library.task.value.busy) return
        val content = com.wynh.appapp.databinding.DialogLibraryImportBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_AppApp_BottomSheetDialog)
        dialog.setContentView(content.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun submit() {
            val input = content.githubUrl.text.toString()
            val url = try {
                GitHubFolderImporter.normalizeLink(input)
            } catch (error: IllegalArgumentException) {
                content.githubUrlLayout.error = if (input.isBlank()) getString(R.string.library_github_required)
                    else error.message ?: getString(R.string.library_github_required)
                return
            }
            if (library.task.value.busy) return
            dialog.dismiss()
            library.importGitHub(url)
        }
        content.importClose.setOnClickListener { dialog.dismiss() }
        content.importZip.apply {
            backupActionIcon.setImageResource(R.drawable.ic_archive)
            backupActionTitle.setText(R.string.library_import_zip)
            backupActionSummary.setText(R.string.library_import_zip_desc)
            backupActionSummary.visibility = View.VISIBLE
            root.setOnClickListener {
                if (!library.task.value.busy) {
                    dialog.dismiss()
                    launchZipImport()
                }
            }
        }
        content.githubUrl.doAfterTextChanged { content.githubUrlLayout.error = null }
        content.githubUrl.setOnEditorActionListener { _, action, _ ->
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) false
            else {
                submit()
                true
            }
        }
        content.importGithub.setOnClickListener { submit() }
        dialog.show()
    }

    companion object {
        internal const val EXTRA_SHOW_YOUR_APPS = "show_your_apps"
        private const val STATE_SELECTED_TAB = "library_selected_tab"
    }
}
