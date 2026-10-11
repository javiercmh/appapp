package com.wynh.appapp.workspace

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wynh.appapp.SubappSessionRegistry
import com.wynh.appapp.ai.AppAiApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Retains document operations across rotation; archive work never blocks the UI. */
class SubappBackupController(application: Application) : AndroidViewModel(application) {
    data class Status(val busy: Boolean = false, val message: String = "", val error: Boolean = false)
    private val current = MutableStateFlow(Status())
    val status = current.asStateFlow()
    private val context = application
    private val repository = SubappRepository(application)

    fun clearResult() { if (!current.value.busy) current.value = Status() }
    fun export(uri: Uri) = run("Backing up subapps…") {
        val records = repository.list()
        require(records.none { (context as AppAiApplication).ai.hasRequest(it.id) }) {
            "Finish and save or cancel active AI requests before backing up."
        }
        val temporary = withContext(Dispatchers.IO) { tempDirectory("subapp-backup-") }
        try {
            val staged: List<File>
            records.forEach { SubappSessionRegistry.pause(it.id) }
            try {
                staged = withContext(Dispatchers.IO) { repository.stageBackup(temporary) }
            } finally { records.forEach { SubappSessionRegistry.resume(it.id) } }
            withContext(Dispatchers.IO) {
                val archive = File(temporary, "subapps.zip")
                SubappBackupArchive.create(staged, archive)
                verify(archive.inputStream(), temporary, "verify-local")
                context.contentResolver.openOutputStream(uri, "wt")?.use { output -> archive.inputStream().use { it.copyTo(output) } }
                    ?: error("Cannot write selected backup destination")
                val count = verify(context.contentResolver.openInputStream(uri) ?: error("Cannot reopen saved backup for verification"), temporary, "verify-saved", com.wynh.appapp.updates.digest(archive))
                "Backed up $count subapps. Saved archive verified."
            }
        } finally { withContext(Dispatchers.IO) { temporary.deleteRecursively() } }
    }

    fun restore(uri: Uri) = run("Restoring subapp copies…") {
        withContext(Dispatchers.IO) {
            val temporary = tempDirectory("subapp-restore-")
            try {
                val workspaces = SubappBackupArchive.validate(
                    context.contentResolver.openInputStream(uri) ?: error("Cannot read selected backup"), temporary)
                val restored = repository.importBackup(workspaces)
                "Restored ${restored.size} subapps as new copies. Existing apps were kept."
            } finally { temporary.deleteRecursively() }
        }
    }

    private fun run(message: String, operation: suspend () -> String) {
        if (current.value.busy) return
        current.value = Status(busy = true, message = message)
        viewModelScope.launch {
            try { current.value = Status(message = operation()) }
            catch (e: Exception) { current.value = Status(message = e.message ?: "Backup operation failed", error = true) }
        }
    }

    private fun tempDirectory(prefix: String): File = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), prefix).toFile()
    private fun verify(input: java.io.InputStream, parent: File, name: String, expectedHash: String? = null): Int {
        val directory = File(parent, name)
        check(directory.mkdirs())
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val count = SubappBackupArchive.validate(java.security.DigestInputStream(input, digest), directory).size
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(expectedHash == null || actual == expectedHash) { "Saved backup differs from the exported archive" }
            count
        } finally { directory.deleteRecursively() }
    }
}
