package com.wynh.appapp

import android.Manifest
import android.app.Dialog
import android.os.Build
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.wynh.appapp.settings.AppSettingsManager
import android.content.Intent
import android.graphics.BitmapFactory
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
import org.json.JSONObject

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
    private var rowsReady = false
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importApp(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SubappRepository(this)
        repository.ensureMigrated()
        showLibrary()
    }

    override fun onResume() {
        super.onResume()
        (application as? com.wynh.appapp.ai.AppAiApplication)?.ai?.auth?.setForeground(true)
        if (rowsReady) refreshRows()
        refreshSettingsPermissions?.invoke()
    }

    override fun onPause() {
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
        binding.newApp.setOnClickListener { edit(repository.createStarter()) }
        binding.importApp.setOnClickListener {
            showImportOptions()
        }
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
        dialog.show()
        com.wynh.appapp.ai.AiSettingsController.bind(this, dialog)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun refreshRows() {
        binding.appList.removeAllViews()
        val records = repository.list()
        binding.appCount.text = resources.getQuantityString(R.plurals.library_count, records.size, records.size)
        binding.emptyState.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        records.forEach { record ->
            val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
            val manifest = try { JSONObject(manager.readFile("manifest.json")) } catch (_: Exception) { JSONObject() }
            val name = manifest.optString("name").ifBlank { getString(R.string.library_untitled) }
            val row = ItemSubappBinding.inflate(layoutInflater, binding.appList, false)
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
            row.editApp.contentDescription = getString(R.string.library_edit_app, name)
            row.appCard.contentDescription = getString(R.string.library_open_app, name)
            row.appCard.setOnClickListener { open(record) }
            row.editApp.setOnClickListener { edit(record) }
            row.installApp.contentDescription = getString(R.string.library_install_app, name)
            row.installApp.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id)
                    .putExtra(MainActivity.EXTRA_OPEN_APP_CONFIG, true))
            }
            row.appMenu.contentDescription = getString(R.string.library_menu, name)
            row.appMenu.setOnClickListener { anchor ->
                val content = layoutInflater.inflate(R.layout.popup_library_actions, null)
                val popup = PopupWindow(content, resources.getDimensionPixelSize(R.dimen.library_actions_popup_width), resources.getDimensionPixelSize(R.dimen.library_actions_popup_height), true).apply {
                    elevation = resources.getDimension(R.dimen.library_actions_popup_elevation)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    isOutsideTouchable = true
                }
                content.findViewById<View>(R.id.delete_app).setOnClickListener {
                    popup.dismiss()
                    confirmDelete(record, name)
                }
                content.findViewById<View>(R.id.rename_app).setOnClickListener {
                    popup.dismiss()
                    showRename(record, name)
                }
                popup.showAsDropDown(anchor, -resources.getDimensionPixelSize(R.dimen.library_actions_popup_width) + anchor.width, 0)
            }
            binding.appList.addView(row.root)
        }
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

    private fun showRename(record: SubappRecord, name: String) {
        val content = com.wynh.appapp.databinding.DialogLibraryRenameBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(content.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        content.renameName.setText(name)
        content.renameName.selectAll()
        content.renameClose.setOnClickListener { dialog.dismiss() }
        content.renameCancel.setOnClickListener { dialog.dismiss() }
        val save = {
            val newName = content.renameName.text.toString().trim()
            if (newName.isBlank()) {
                content.renameNameLayout.error = getString(R.string.library_name_required)
            } else {
                val saved = runCatching {
                    val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
                    val manifest = JSONObject(manager.readFile("manifest.json"))
                    manifest.put("name", newName)
                    manager.writeFile("manifest.json", manifest.toString(2))
                }.getOrDefault(false)
                if (saved) {
                    dialog.dismiss()
                    refreshRows()
                } else {
                    content.renameNameLayout.error = getString(R.string.library_rename_failed)
                }
            }
        }
        content.renameSave.setOnClickListener { save() }
        content.renameName.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else false
        }
        dialog.show()
        dialog.window?.setLayout(
            minOf((resources.displayMetrics.widthPixels * 0.94).toInt(), (resources.displayMetrics.density * 480).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        content.renameName.requestFocus()
    }

    private fun confirmDelete(record: SubappRecord, name: String) {
        AlertDialog.Builder(this).setTitle("Delete $name?")
            .setMessage("Its files, data, history, and home-screen shortcut will be removed.")
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

    private fun showImportOptions() {
        val content = com.wynh.appapp.databinding.DialogLibraryImportBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(content.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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
        binding.importApp.isEnabled = false
        Toast.makeText(this, R.string.library_github_downloading, Toast.LENGTH_SHORT).show()
        Thread {
            val result = com.wynh.appapp.workspace.GitHubFolderImporter.download(this, url)
            runOnUiThread {
                binding.importApp.isEnabled = true
                result.onSuccess { folder ->
                    val record = runCatching { repository.createImportedFrom(folder) }
                    folder.deleteRecursively()
                    record.onSuccess {
                        refreshRows()
                        val manager = WorkspaceManager(this, workspaceDir = it.workspaceDir)
                        val name = runCatching { JSONObject(manager.readFile("manifest.json")).optString("name") }
                            .getOrNull().orEmpty().ifBlank { getString(R.string.library_untitled) }
                        Toast.makeText(this, getString(R.string.library_imported_app, name), Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
                }.onFailure { Toast.makeText(this, "GitHub import failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
