package com.wynh.appapp.workspace

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wynh.appapp.R
import com.wynh.appapp.host.HostCompatibility
import com.wynh.appapp.marketplace.CatalogSource
import com.wynh.appapp.marketplace.InstalledProvenance
import com.wynh.appapp.marketplace.MarketplaceApp
import com.wynh.appapp.marketplace.MarketplaceCatalogRepository
import com.wynh.appapp.marketplace.MarketplaceMatcher
import com.wynh.appapp.updates.GitHubSubappInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Library metadata and document operations survive rotation; activities render state only. */
class LibraryController(private val app: Application) : AndroidViewModel(app) {
    data class Catalog(
        val records: List<SubappRecord> = emptyList(),
        val metadata: Map<String, SubappMetadata> = emptyMap(),
        val error: String? = null,
        val revision: Int = 0,
        val provenance: Map<String, InstalledProvenance> = emptyMap(),
    )

    data class Task(
        val busy: Boolean = false,
        val message: String = "",
        val error: Boolean = false,
        val share: File? = null,
        val created: SubappRecord? = null,
        val cancellable: Boolean = false,
        val deleted: String? = null,
        val installedFromCatalog: SubappRecord? = null,
        val installedName: String? = null,
        val installedOfflineNote: Boolean = false,
        val detail: String = "",
    )

    data class MarketplaceState(
        val apps: List<MarketplaceApp> = emptyList(),
        val syncedFromRemote: Boolean = false,
        val refreshing: Boolean = false,
        val refreshError: String? = null,
        val cachedIcons: Map<String, Bitmap> = emptyMap(),
    )

    private val catalogValue = MutableStateFlow(Catalog())
    val catalog = catalogValue.asStateFlow()
    private val taskValue = MutableStateFlow(Task())
    val task = taskValue.asStateFlow()
    private val marketplaceValue = MutableStateFlow(MarketplaceState())
    val marketplace = marketplaceValue.asStateFlow()

    private val repository = SubappRepository(app)
    private var revision = 0
    private val gate = PublicationGate()
    private val workerLock = Any()
    private var worker: Thread? = null

    private val defaultCatalogRepository by lazy { MarketplaceCatalogRepository(app) }

    @VisibleForTesting
    internal var catalogRepositoryOverride: MarketplaceCatalogRepository? = null

    @VisibleForTesting
    internal var subappInstallerOverride: GitHubSubappInstaller? = null

    private fun catalogRepository() = catalogRepositoryOverride ?: defaultCatalogRepository

    init {
        loadMarketplaceLocal()
    }

    /** Loads the bundled or previously cached catalog with zero network requests. */
    @VisibleForTesting
    internal fun loadMarketplaceLocal() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val result = catalogRepository().loadLocal()
                    result to decodeIcons(result.iconFiles)
                }.getOrNull()
            } ?: return@launch
            val (result, icons) = loaded
            marketplaceValue.update {
                it.copy(
                    apps = result.apps,
                    syncedFromRemote = result.syncedFromRemote,
                    cachedIcons = icons,
                )
            }
        }
    }

    /** Manually fetches the remote catalog JSON from GitHub on user request. */
    fun refreshMarketplace() {
        if (marketplaceValue.value.refreshing) return
        marketplaceValue.update { it.copy(refreshing = true, refreshError = null) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val result = catalogRepository().refreshFromRemote()
                    result to decodeIcons(result.iconFiles)
                }
            }
            outcome.onSuccess { (result, icons) ->
                marketplaceValue.value = MarketplaceState(
                    apps = result.apps,
                    syncedFromRemote = result.syncedFromRemote,
                    refreshing = false,
                    refreshError = null,
                    cachedIcons = icons,
                )
            }.onFailure { error ->
                Log.w("LibraryController", "Catalog refresh failed", error)
                marketplaceValue.update {
                    it.copy(
                        refreshing = false,
                        refreshError = app.getString(R.string.marketplace_refresh_failed),
                    )
                }
            }
        }
    }

    fun clearMarketplaceRefreshError() {
        marketplaceValue.update { if (it.refreshError != null) it.copy(refreshError = null) else it }
    }

    /** Stops an import before publication: the worker is interrupted, and publication is refused once cancelled. */
    fun cancel() {
        if (!taskValue.value.cancellable || !gate.cancel()) return
        synchronized(workerLock) { worker?.interrupt() }
        taskValue.update {
            if (it.busy) it.copy(message = app.getString(R.string.library_import_cancelling), cancellable = false)
            else it
        }
    }

    private fun beforePublication() {
        check(gate.beginPublication()) { app.getString(R.string.library_import_cancelled) }
        taskValue.update { if (it.busy) it.copy(cancellable = false) else it }
    }

    fun refresh() {
        val version = ++revision
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val records = repository.ensureMigrated()
                    val previous = catalogValue.value.metadata
                    val metadata = records.associate { it.id to SubappMetadataLoader.load(it.workspaceDir, previous[it.id]) }
                    val provenance = records.associate { it.id to MarketplaceMatcher.inspectInstalled(it) }
                    Catalog(records, metadata, provenance = provenance)
                }.getOrElse { Catalog(error = it.message ?: app.getString(R.string.library_load_failed)) }
            }
            // Emit even an unchanged disk catalog to reconcile optimistic drag order after failure.
            if (version == revision) catalogValue.value = result.copy(revision = version)
        }
    }

    override fun onCleared() {
        if (gate.cancel()) synchronized(workerLock) { worker?.interrupt() }
        super.onCleared()
    }

    fun clearTask() {
        if (!taskValue.value.busy) taskValue.value = Task()
    }

    private fun run(message: String, cancellable: Boolean = false, action: () -> Task) {
        if (taskValue.value.busy) return
        gate.reset()
        taskValue.value = Task(busy = true, message = message, cancellable = cancellable)
        viewModelScope.launch {
            taskValue.value = withContext(Dispatchers.IO) {
                synchronized(workerLock) { worker = Thread.currentThread() }
                try {
                    runCatching(action).getOrElse {
                        Task(message = it.message ?: app.getString(R.string.workspace_operation_failed), error = true)
                    }
                } finally {
                    synchronized(workerLock) { worker = null }
                    Thread.interrupted() // a late cancel must not leak into the pooled thread
                }
            }
            refresh()
        }
    }

    fun reorder(ids: List<String>) = run(app.getString(R.string.library_task_reorder)) {
        repository.reorder(ids)
        Task()
    }

    fun delete(id: String) = run(app.getString(R.string.library_task_delete)) {
        check(repository.delete(id)) { app.getString(R.string.library_delete_failed) }
        Task(message = app.getString(R.string.library_deleted_notice), deleted = id)
    }

    fun recover() = run(app.getString(R.string.library_task_recover)) {
        repository.recoverLibrary()
        Task(message = app.getString(R.string.library_recovered_notice))
    }

    fun create() = run(app.getString(R.string.library_task_create)) {
        Task(message = app.getString(R.string.library_created_notice), created = repository.createStarter())
    }

    fun installCatalogApp(item: MarketplaceApp) {
        when (val source = item.source) {
            CatalogSource.BundledStarter -> run(app.getString(R.string.library_task_create)) {
                requireNotInstalled(item)
                val provenance = JSONObject().put("catalogId", item.id).toString()
                val record = repository.createStarter(provenance)
                Task(
                    message = app.getString(R.string.marketplace_installed_notice, item.name),
                    installedFromCatalog = record,
                    installedName = item.name,
                    installedOfflineNote = false,
                )
            }
            is CatalogSource.GitHub -> run(
                app.getString(R.string.marketplace_installing_app, item.name),
                cancellable = true,
            ) {
                requireNotInstalled(item)
                val hostLevel = HostCompatibility.LEVEL.toLong()
                require(item.minHostCompatibility <= hostLevel) {
                    app.getString(R.string.marketplace_host_required, item.minHostCompatibility, hostLevel)
                }
                val installer = subappInstallerOverride ?: GitHubSubappInstaller(app.cacheDir)
                val downloaded = installer.download(source.source, hostLevel) { done, total ->
                    taskValue.update { current ->
                        if (current.busy) current.copy(detail = app.getString(R.string.marketplace_download_progress, done, total))
                        else current
                    }
                }
                try {
                    val provenance = JSONObject()
                        .put("url", source.repoUrl)
                        .put("commit", downloaded.commit)
                        .put("catalogId", item.id)
                        .toString()
                    val updateStateJson = downloaded.installedUpdate?.let(GitHubSubappInstaller::serializeUpdateState)
                    requireNotInstalled(item)
                    beforePublication()
                    val record = repository.createImportedFrom(
                        sourceWorkspace = downloaded.directory,
                        provenance = provenance,
                        initialUpdateState = updateStateJson,
                    )
                    Task(
                        message = app.getString(R.string.marketplace_installed_notice, item.name),
                        installedFromCatalog = record,
                        installedName = item.name,
                        installedOfflineNote = true,
                    )
                } finally {
                    downloaded.directory.deleteRecursively()
                }
            }
        }
    }

    /**
     * Discover installs one copy per catalog entry; more copies come only from Your apps → +.
     * Checked against the repository on the worker thread, not the last published list, so a tap
     * landing just after another install finished cannot slip through before the list refreshes.
     */
    private fun requireNotInstalled(item: MarketplaceApp) {
        val installed = repository.list().any { record ->
            MarketplaceMatcher.matches(item, MarketplaceMatcher.inspectInstalled(record))
        }
        check(!installed) { app.getString(R.string.marketplace_already_installed, item.name) }
    }

    fun importZip(uri: Uri) = run(app.getString(R.string.library_task_zip), cancellable = true) {
        val staged = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "zip-app-").toFile()
        try {
            (app.contentResolver.openInputStream(uri) ?: error("Cannot read selected ZIP")).use {
                WorkspacePackageManager.extractZip(it, staged)
            }
            beforePublication()
            repository.createImportedFrom(staged)
            Task(message = app.getString(R.string.library_import_offline_note))
        } finally {
            staged.deleteRecursively()
        }
    }

    fun importGitHub(url: String) = run(app.getString(R.string.library_task_github), cancellable = true) {
        val imported = GitHubFolderImporter.download(app.cacheDir, url).getOrThrow()
        try {
            val source = JSONObject().put("url", "https://github.com" + java.net.URL(url).path).put("commit", imported.commit).toString()
            beforePublication()
            repository.createImportedFrom(imported.directory, source)
            Task(message = app.getString(R.string.library_import_offline_note))
        } finally {
            imported.directory.deleteRecursively()
        }
    }

    fun export(id: String, selected: Set<String>, destination: Uri?, name: String) = run(app.getString(R.string.library_task_export)) {
        val record = repository.resolve(id) ?: error("App no longer available")
        // The workspace lock is held only while the ZIP is built in the cache. A destination such as a
        // cloud provider can be slow, and must never stall the running app's bridge or the log worker.
        val manager = WorkspaceManager(app, workspaceDir = record.workspaceDir)
        val zip = synchronized(com.wynh.appapp.updates.WorkspaceLocks.forWorkspace(record.workspaceDir)) {
            WorkspacePackageManager.exportToZipFile(app, manager, selected, name)
        }
        if (destination == null) Task(message = app.getString(R.string.library_packaged_notice), share = zip)
        else try {
            val output = app.contentResolver.openOutputStream(destination, "wt") ?: error("Cannot write destination")
            output.use { sink -> zip.inputStream().use { it.copyTo(sink) } }
            Task(message = app.getString(R.string.library_exported_notice))
        } finally {
            zip.delete()
        }
    }

    private fun decodeIcons(iconFiles: Map<String, File>): Map<String, Bitmap> = iconFiles.mapNotNull { (id, file) ->
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            options.inSampleSize = 1
            while (options.outWidth / options.inSampleSize > 192 || options.outHeight / options.inSampleSize > 192) {
                options.inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.path, options)?.let { id to it }
        }.getOrNull()
    }.toMap()
}
