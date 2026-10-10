package com.example.runtimecompiler

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
import com.example.runtimecompiler.databinding.ActivitySubappLibraryBinding
import com.example.runtimecompiler.databinding.ItemSubappBinding
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.appcompat.app.AlertDialog
import com.example.runtimecompiler.bridge.NativeStorageBridge
import com.example.runtimecompiler.workspace.SubappRecord
import com.example.runtimecompiler.workspace.SubappRepository
import com.example.runtimecompiler.workspace.WorkspaceManager
import org.json.JSONObject

class SubappLibraryActivity : AppCompatActivity() {
    private lateinit var repository: SubappRepository
    private lateinit var binding: ActivitySubappLibraryBinding
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
        if (rowsReady) refreshRows()
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
                repository.list().firstOrNull()?.let { record ->
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SUBAPP_ID, record.id)
                        .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))
                }
                true
            } else false
        }
        binding.newApp.setOnClickListener { edit(repository.createStarter()) }
        binding.importApp.setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        rowsReady = true
        refreshRows()
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
                val content = layoutInflater.inflate(R.layout.popup_library_delete, null)
                val popup = PopupWindow(content, resources.getDimensionPixelSize(R.dimen.library_delete_popup_width), resources.getDimensionPixelSize(R.dimen.library_delete_popup_height), true).apply {
                    elevation = resources.getDimension(R.dimen.library_delete_popup_elevation)
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    isOutsideTouchable = true
                }
                content.setOnClickListener {
                    popup.dismiss()
                    confirmDelete(record, name)
                }
                popup.showAsDropDown(anchor, -resources.getDimensionPixelSize(R.dimen.library_delete_popup_width) + anchor.width, 0)
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
        val history = com.example.runtimecompiler.workspace.WorkspaceHistoryManager(this, appDirectory = record.directory)
        val result = com.example.runtimecompiler.workspace.WorkspacePackageManager.importFromZip(
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
}
