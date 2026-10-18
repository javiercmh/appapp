package com.wynh.appapp.workspace

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

/** Library metadata and document operations survive rotation; activities render state only. */
class LibraryController(private val app: Application) : AndroidViewModel(app) {
    data class Catalog(val records: List<SubappRecord> = emptyList(), val metadata: Map<String, SubappMetadata> = emptyMap(), val error: String? = null, val revision: Int = 0)
    data class Task(val busy: Boolean = false, val message: String = "", val error: Boolean = false, val share: File? = null, val created: SubappRecord? = null, val cancellable: Boolean = false, val deleted: String? = null)
    private val catalogValue = MutableStateFlow(Catalog())
    val catalog = catalogValue.asStateFlow()
    private val taskValue = MutableStateFlow(Task())
    val task = taskValue.asStateFlow()
    private val repository = SubappRepository(app)
    private var revision = 0
    private val gate = PublicationGate()
    private val workerLock = Any()
    private var worker: Thread? = null

    /** Stops an import before publication: the worker is interrupted, and publication is refused once cancelled. */
    fun cancel() {
        if (!taskValue.value.cancellable || !gate.cancel()) return
        synchronized(workerLock) { worker?.interrupt() }
        taskValue.value = taskValue.value.copy(message = app.getString(com.wynh.appapp.R.string.library_import_cancelling), cancellable = false)
    }
    private fun beforePublication() {
        check(gate.beginPublication()) { app.getString(com.wynh.appapp.R.string.library_import_cancelled) }
        taskValue.value = taskValue.value.copy(cancellable = false)
    }

    fun refresh() {
        val version = ++revision
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val records = repository.ensureMigrated()
                    Catalog(records, records.associate { it.id to SubappMetadataLoader.load(it.workspaceDir, catalogValue.value.metadata[it.id]) })
                }.getOrElse { Catalog(error = it.message ?: app.getString(com.wynh.appapp.R.string.library_load_failed)) }
            }
            // Emit even an unchanged disk catalog to reconcile optimistic drag order after failure.
            if (version == revision) catalogValue.value = result.copy(revision = version)
        }
    }
    override fun onCleared() { if (gate.cancel()) synchronized(workerLock) { worker?.interrupt() }; super.onCleared() }
    fun clearTask() { if (!taskValue.value.busy) taskValue.value = Task() }
    private fun run(message: String, cancellable: Boolean = false, action: () -> Task) {
        if (taskValue.value.busy) return
        gate.reset()
        taskValue.value = Task(busy = true, message = message, cancellable = cancellable)
        viewModelScope.launch {
            taskValue.value = withContext(Dispatchers.IO) {
                synchronized(workerLock) { worker = Thread.currentThread() }
                try {
                    runCatching(action).getOrElse { Task(message = it.message ?: app.getString(com.wynh.appapp.R.string.workspace_operation_failed), error = true) }
                } finally {
                    synchronized(workerLock) { worker = null }
                    Thread.interrupted() // a late cancel must not leak into the pooled thread
                }
            }
            refresh()
        }
    }
    fun reorder(ids: List<String>) = run(app.getString(com.wynh.appapp.R.string.library_task_reorder)) { repository.reorder(ids); Task() }
    fun delete(id: String) = run(app.getString(com.wynh.appapp.R.string.library_task_delete)) { check(repository.delete(id)) { app.getString(com.wynh.appapp.R.string.library_delete_failed) }; Task(message = app.getString(com.wynh.appapp.R.string.library_deleted_notice), deleted = id) }
    fun recover() = run(app.getString(com.wynh.appapp.R.string.library_task_recover)) { repository.recoverLibrary(); Task(message = app.getString(com.wynh.appapp.R.string.library_recovered_notice)) }
    fun create() = run(app.getString(com.wynh.appapp.R.string.library_task_create)) { Task(message = app.getString(com.wynh.appapp.R.string.library_created_notice), created = repository.createStarter()) }
    fun importZip(uri: Uri) = run(app.getString(com.wynh.appapp.R.string.library_task_zip), cancellable = true) {
        val staged = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "zip-app-").toFile()
        try {
            (app.contentResolver.openInputStream(uri) ?: error("Cannot read selected ZIP")).use { WorkspacePackageManager.extractZip(it, staged) }
            beforePublication()
            repository.createImportedFrom(staged)
            Task(message = app.getString(com.wynh.appapp.R.string.library_import_offline_note))
        } finally { staged.deleteRecursively() }
    }
    fun importGitHub(url: String) = run(app.getString(com.wynh.appapp.R.string.library_task_github), cancellable = true) {
        val imported = GitHubFolderImporter.download(app.cacheDir, url).getOrThrow()
        try {
            val source = JSONObject().put("url", "https://github.com" + java.net.URL(url).path).put("commit", imported.commit).toString()
            beforePublication()
            repository.createImportedFrom(imported.directory, source)
            Task(message = app.getString(com.wynh.appapp.R.string.library_import_offline_note))
        } finally { imported.directory.deleteRecursively() }
    }
    fun export(id: String, selected: Set<String>, destination: Uri?, name: String) = run(app.getString(com.wynh.appapp.R.string.library_task_export)) {
        val record = repository.resolve(id) ?: error("App no longer available")
        // The workspace lock is held only while the ZIP is built in the cache. A destination such as a
        // cloud provider can be slow, and must never stall the running app's bridge or the log worker.
        val manager = WorkspaceManager(app, workspaceDir = record.workspaceDir)
        val zip = synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(record.workspaceDir)) {
            WorkspacePackageManager.exportToZipFile(app, manager, selected, name)
        }
        if (destination == null) Task(message = app.getString(com.wynh.appapp.R.string.library_packaged_notice), share = zip)
        else try {
            val output = app.contentResolver.openOutputStream(destination, "wt") ?: error("Cannot write destination")
            output.use { sink -> zip.inputStream().use { it.copyTo(sink) } }
            Task(message = app.getString(com.wynh.appapp.R.string.library_exported_notice))
        } finally { zip.delete() }
    }
}
