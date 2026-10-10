package com.example.runtimecompiler

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.appcompat.app.AlertDialog
import com.example.runtimecompiler.bridge.NativeStorageBridge
import com.example.runtimecompiler.workspace.SubappRecord
import com.example.runtimecompiler.workspace.SubappRepository
import com.example.runtimecompiler.workspace.WorkspaceManager
import org.json.JSONObject

class SubappLibraryActivity : AppCompatActivity() {
    private lateinit var repository: SubappRepository
    private lateinit var listContainer: LinearLayout
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 12) }
        root.addView(TextView(this).apply { text = "Your apps"; textSize = 28f })
        root.addView(TextView(this).apply { text = "Each app keeps its own files and data."; textSize = 14f; setPadding(0, 4, 0, 16) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "New starter app"; setOnClickListener { edit(repository.createStarter()) } })
        actions.addView(Button(this).apply { text = "Import ZIP"; setOnClickListener { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) } })
        root.addView(actions)
        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        rowsReady = true
        title = "App²"
        refreshRows()
    }

    private fun refreshRows() {
        listContainer.removeAllViews()
        repository.list().forEach { record ->
            val manager = WorkspaceManager(this, workspaceDir = record.workspaceDir)
            val manifest = try { JSONObject(manager.readFile("manifest.json")) } catch (_: Exception) { JSONObject() }
            val name = manifest.optString("name", "Untitled app")
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 14, 4, 10) }
            val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val iconName = manifest.optJSONArray("icons")?.optJSONObject(0)?.optString("src") ?: "icon.png"
            val iconFile = manager.getFile(iconName)
            if (iconFile.isFile) {
                val image = ImageView(this).apply { setImageBitmap(BitmapFactory.decodeFile(iconFile.absolutePath)); scaleType = ImageView.ScaleType.CENTER_CROP }
                heading.addView(image, LinearLayout.LayoutParams(52, 52))
            } else {
                heading.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_round) }, LinearLayout.LayoutParams(52, 52))
            }
            heading.addView(TextView(this).apply { text = name; textSize = 18f; setPadding(12, 0, 8, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(heading)
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply { text = "Open"; setOnClickListener { open(record) } }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this).apply { text = "Edit"; setOnClickListener { edit(record) } }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this).apply { text = "Install"; setOnClickListener { install(record, name, iconFile) } }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(Button(this).apply { text = "Delete"; setOnClickListener { confirmDelete(record, name) } }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actions)
            listContainer.addView(row)
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

    private fun install(record: SubappRecord, name: String, iconFile: java.io.File) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Toast.makeText(this, R.string.shortcut_not_supported, Toast.LENGTH_LONG).show(); return
        }
        val icon = if (iconFile.isFile) runCatching { IconCompat.createWithBitmap(BitmapFactory.decodeFile(iconFile.absolutePath)) }.getOrNull() else null
        val info = ShortcutInfoCompat.Builder(this, "appapp_subapp_${record.id}")
            .setShortLabel(name.take(25)).setLongLabel(name)
            .setIcon(icon ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher_round))
            .setIntent(Intent(this, SubappRuntimeActivity::class.java).apply {
                action = Intent.ACTION_VIEW; data = Uri.parse("appapp://subapp/${record.id}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
            }).build()
        ShortcutManagerCompat.requestPinShortcut(this, info, null)
        Toast.makeText(this, getString(R.string.shortcut_requested, name), Toast.LENGTH_SHORT).show()
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
        val result = com.example.runtimecompiler.workspace.WorkspacePackageManager.importFromZip(this, uri, manager, history)
        result.onSuccess { refreshRows(); Toast.makeText(this, "App imported", Toast.LENGTH_SHORT).show() }
            .onFailure { repository.delete(record.id); Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
}
