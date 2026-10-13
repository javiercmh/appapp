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
import com.wynh.appapp.workspace.SubappRecord
import com.wynh.appapp.workspace.SubappRepository
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
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wynh.appapp.workspace.SubappBackupController
import org.json.JSONObject
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.wynh.appapp.databinding.LibraryHeaderBinding

class SubappLibraryActivity : AppCompatActivity() {
    private lateinit var repository: SubappRepository
    private lateinit var binding: ActivitySubappLibraryBinding
    private val settingsManager by lazy { AppSettingsManager(this) }
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
    private var pendingExportFiles: Set<String>? = null
    private var pendingExportId: String? = null
    private val saveZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val filesToExport = pendingExportFiles
        val record = pendingExportId?.let(repository::resolve)
        if (uri != null && filesToExport != null && record != null) {
            try {
                (contentResolver.openOutputStream(uri) ?: error("Cannot open destination")).use { os ->
                    WorkspacePackageManager.exportToOutputStream(WorkspaceManager(this, workspaceDir = record.workspaceDir), filesToExport, os)
                }
                android.util.Log.d("AppApp", "[Package] Saved ${filesToExport.size} files to ZIP via Storage Access Framework.")
                Toast.makeText(this, getString(R.string.export_success, filesToExport.size), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.d("AppApp", "[Package Error] Failed to write ZIP: ${e.message}")
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportFiles = null
        pendingExportId = null
    }

    private fun showPackageDialog(record: SubappRecord) {
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
        val btnSaveZip = dialog.findViewById<MaterialButton>(R.id.package_btn_save_zip)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.package_btn_close)

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
                android.util.Log.d("AppApp", "[Package] Exported ${selectedFiles.size} files to '$customPackageName.zip' and triggered Sharesheet.")
            } catch (e: Exception) {
                android.util.Log.d("AppApp", "[Package Error] Export failed: ${e.message}")
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
            pendingExportId = record.id
            saveZipLauncher.launch("$customPackageName.zip")
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private var rowsReady = false
    private val displayedRecords = mutableListOf<SubappRecord>()
    private lateinit var header: LibraryHeaderBinding
    private lateinit var libraryAdapter: LibraryAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var reorderOriginal = emptyList<SubappRecord>()
    private var dragCancelled = false
    private val stableIds = mutableMapOf<String, Long>()
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importApp(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SubappRepository(this)
        repository.ensureMigrated()
        pendingExportId = savedInstanceState?.getString("export_app_id")
        pendingExportFiles = savedInstanceState?.getStringArrayList("export_files")?.toSet()
        showLibrary()
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
        outState.putString("export_app_id", pendingExportId)
        pendingExportFiles?.let { outState.putStringArrayList("export_files", ArrayList(it)) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(true)
        if (rowsReady) refreshRows()
        refreshSettingsPermissions?.invoke()
    }

    override fun onDestroy() {
        backupProgress?.dismiss()
        backupProgress = null
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
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.libraryToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                showLibrarySettings()
                true
            } else false
        }
        binding.appList.layoutManager = LinearLayoutManager(this)
        header = LibraryHeaderBinding.inflate(layoutInflater, binding.appList, false)
        libraryAdapter = LibraryAdapter()
        binding.appList.adapter = libraryAdapter
        binding.appList.onDragCancelled = { dragCancelled = true }
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
        rowsReady = true
        refreshRows()
    }

    /** Opens library-wide preferences without creating or selecting a subapp workspace. */
    private fun showLibrarySettings() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_library_settings)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnFontSmall = dialog.findViewById<MaterialButton>(R.id.settings_btn_font_small)
        val btnFontNormal = dialog.findViewById<MaterialButton>(R.id.settings_btn_font_normal)
        val btnFontLarge = dialog.findViewById<MaterialButton>(R.id.settings_btn_font_large)
        val fontButtons = mapOf(
            AppSettingsManager.FONT_SIZE_SMALL to btnFontSmall,
            AppSettingsManager.FONT_SIZE_NORMAL to btnFontNormal,
            AppSettingsManager.FONT_SIZE_LARGE to btnFontLarge
        )
        fun updateFontButtonsUI(selectedSize: Float) {
            fontButtons.forEach { (size, button) ->
                button ?: return@forEach
                val selected = Math.abs(size - selectedSize) < 0.5f
                button.setBackgroundColor(ContextCompat.getColor(this, if (selected) R.color.primary else R.color.surface_elevated))
                button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.on_surface_muted))
            }
        }
        updateFontButtonsUI(settingsManager.editorFontSizeSp)
        fontButtons.forEach { (size, button) -> button?.setOnClickListener {
            settingsManager.editorFontSizeSp = size
            updateFontButtonsUI(size)
            Toast.makeText(this, "Editor font size updated", Toast.LENGTH_SHORT).show()
        } }


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
        refreshSettingsPermissions = { refreshNotificationRow(); refreshExactAlarmRow(); Unit }
        dialog.setOnDismissListener { refreshSettingsPermissions = null }
        btnExactAlarms?.setOnClickListener {
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

    private fun refreshRows() {
        if (reorderOriginal.isNotEmpty()) return
        val records = repository.list()
        displayedRecords.clear()
        displayedRecords.addAll(records)
        header.appCount.text = resources.getQuantityString(R.plurals.library_count, records.size, records.size)
        header.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        libraryAdapter.notifyDataSetChanged()
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
                if (displayedRecords.size < 2 || holder.bindingAdapterPosition == RecyclerView.NO_POSITION) false
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
            val manager = WorkspaceManager(this@SubappLibraryActivity, workspaceDir = record.workspaceDir)
            val manifest = try { JSONObject(manager.readFile("manifest.json")) } catch (_: Exception) { JSONObject() }
            val name = manifest.optString("name").ifBlank { getString(R.string.library_untitled) }
            val iconName = manifest.optJSONArray("icons")?.optJSONObject(0)?.optString("src")?.takeIf { it.isNotBlank() } ?: "icon.png"
            val iconFile = manager.getFile(iconName)
            val bitmap = if (iconFile.isFile) runCatching { BitmapFactory.decodeFile(iconFile.absolutePath) }.getOrNull() else null
            row.appIcon.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, resources.displayMetrics.density * 12f)
                .build()
            if (bitmap != null) row.appIcon.setImageBitmap(bitmap)
            else row.appIcon.setImageResource(R.mipmap.ic_launcher_round)
            row.appName.text = name
            row.appDescription.text = manifest.optString("description").ifBlank { getString(R.string.library_app_fallback) }
            row.appCard.contentDescription = getString(R.string.library_open_app, name)
            row.appCard.setOnClickListener { open(record) }
            row.appMenu.contentDescription = getString(R.string.library_menu, name)
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
                content.findViewById<View>(R.id.update_app).setOnClickListener {
                    popup.dismiss()
                    com.wynh.appapp.updates.UpdateController(this@SubappLibraryActivity, record) { refreshRows() }.open()
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
                content.findViewById<View>(R.id.share_app).setOnClickListener { popup.dismiss(); showPackageDialog(record) }
                content.findViewById<View>(R.id.edit_app).setOnClickListener { popup.dismiss(); edit(record) }
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
        runCatching { repository.reorder(displayedRecords.map { it.id }) }.onFailure {
            displayedRecords.clear()
            displayedRecords.addAll(original)
            binding.appList.post { libraryAdapter.notifyDataSetChanged() }
            Toast.makeText(this, R.string.library_reorder_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveByAccessibility(holder: AppHolder, delta: Int): Boolean {
        if (reorderOriginal.isNotEmpty()) return false
        val from = holder.bindingAdapterPosition - 1
        if (from !in displayedRecords.indices) return false
        val to = (from + delta).coerceIn(0, displayedRecords.lastIndex)
        if (from == to) return false
        val item = displayedRecords.removeAt(from)
        displayedRecords.add(to, item)
        runCatching { repository.reorder(displayedRecords.map { it.id }) }.onFailure {
            displayedRecords.removeAt(to); displayedRecords.add(from, item)
            Toast.makeText(this, R.string.library_reorder_failed, Toast.LENGTH_SHORT).show()
            return false
        }
        libraryAdapter.notifyItemMoved(from + 1, to + 1)
        return true
    }

    private fun open(record: SubappRecord) {
        startActivity(Intent(this, SubappRuntimeActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("appapp://subapp/${record.id}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
        })
    }

    private fun edit(record: SubappRecord) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id))
    }

    private fun confirmDelete(record: SubappRecord, name: String) {
        AlertDialog.Builder(this).setTitle("Delete $name?")
            .setMessage("Its files, data, and history will be removed. App² will disable its shortcut.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                ShortcutManagerCompat.disableShortcuts(this, listOf("appapp_subapp_${record.id}"), "App no longer available")
                SubappSessionRegistry.close(record.id)
                NativeStorageBridge.cancelAllForSubapp(this, record.id)
                if (repository.delete(record.id)) refreshRows()
            }.show()
    }

    private fun importApp(uri: Uri) {
        val record = repository.createStarter()
        val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
        val history = com.wynh.appapp.workspace.WorkspaceHistoryManager(this, appDirectory = record.directory)
        val result = com.wynh.appapp.workspace.WorkspacePackageManager.importFromZip(
            this, uri, manager, history, savePreImportSnapshot = false
        )
        result.onSuccess {
            val baselineSaved = history.captureInitialVersion(manager, "ZIP Package", replaceExisting = true)
            refreshRows()
            Toast.makeText(
                this,
                if (baselineSaved) "App imported" else "App imported, but its initial version could not be saved",
                Toast.LENGTH_SHORT
            ).show()
        }
            .onFailure { repository.delete(record.id); Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showNewAppMenu() {
        val content = com.wynh.appapp.databinding.DialogLibraryImportBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(content.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        content.createStarter.setOnClickListener {
            dialog.dismiss()
            edit(repository.createStarter())
        }
        content.importClose.setOnClickListener { dialog.dismiss() }
        content.importZip.setOnClickListener {
            dialog.dismiss()
            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
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
    }

    private fun importFromGitHub(url: String) {
        header.newApp.isEnabled = false
        Toast.makeText(this, R.string.library_github_downloading, Toast.LENGTH_SHORT).show()
        Thread {
            val result = com.wynh.appapp.workspace.GitHubFolderImporter.download(cacheDir, url)
            runOnUiThread {
                header.newApp.isEnabled = true
                result.onSuccess { imported ->
                    val folder = imported.directory
                    val record = runCatching { repository.createImportedFrom(folder) }
                    folder.deleteRecursively()
                    record.onSuccess {
                        refreshRows()
                        val manager = WorkspaceManager(this, workspaceDir = it.workspaceDir)
                        val name = runCatching { JSONObject(manager.readFile("manifest.json")).optString("name") }
                            .getOrNull().orEmpty().ifBlank { getString(R.string.library_untitled) }
                        Toast.makeText(this, getString(R.string.library_imported_app, name) + "\n" + getString(R.string.library_import_offline_note), Toast.LENGTH_LONG).show()
                    }.onFailure { Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }.onFailure { Toast.makeText(this, "GitHub import failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
