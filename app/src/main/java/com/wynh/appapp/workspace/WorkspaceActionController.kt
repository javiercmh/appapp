package com.wynh.appapp.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wynh.appapp.SubappSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One app operation owns its workspace until publication/recovery finishes, including rotation. */
class WorkspaceActionController(private val app: Application) : AndroidViewModel(app) {
    data class EditorDraft(val fileName: String, val initial: String, val text: String)
    var editorDraft: EditorDraft? = null

    enum class Kind { FILE_SAVED, FILE_CREATED, FILE_DELETED, PREVIEW_READY, SETTINGS_SAVED, RESTORED, UPDATED, RESET }

    /**
     * What a successful operation asks the screen to do. It lives in controller state, not in a
     * coroutine of the screen that started it, so a rotation cannot lose it: the next started
     * screen receives it and acknowledges it.
     */
    data class Completion(val kind: Kind, val subject: String = "", val payload: String = "", val id: Long = 0)

    data class State(val busy: Boolean = false, val message: String = "", val error: Boolean = false, val completions: List<Completion> = emptyList())
    private val value = MutableStateFlow(State())
    val state = value.asStateFlow()
    private var sequence = 0L

    fun clear() { if (!value.value.busy) value.update { it.copy(message = "", error = false) } }
    fun acknowledge(id: Long) { value.update { state -> state.copy(completions = state.completions.filterNot { it.id == id }) } }

    /**
     * Runs [action] with the app's workspace reserved. A [replacement] (restore, update, reset)
     * suspends the app's live sessions and makes them reload; any other operation leaves running
     * pages alone. [cleanup] runs exactly once afterwards, also when the operation is refused or
     * fails, so staged files cannot leak. The operation outlives the screen that started it.
     */
    fun execute(
        record: SubappRecord,
        label: String,
        replacement: Boolean = true,
        completion: Completion? = null,
        cleanup: () -> Unit = {},
        action: (WorkspaceManager, WorkspaceHistoryManager) -> Unit
    ) {
        if (value.value.busy) { discard(cleanup); return }
        val lease = try { WorkspaceOperations.reserve(record.workspaceDir, replacement) } catch (e: Exception) {
            value.update { it.copy(message = e.message ?: "Workspace is busy", error = true) }; discard(cleanup); return
        }
        value.update { it.copy(busy = true, message = label, error = false) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: String? = null
            try {
                if (replacement) SubappSessionRegistry.pause(record.id)
                withContext(Dispatchers.IO) {
                    lease.run { action(WorkspaceManager(app, workspaceDir = record.workspaceDir), WorkspaceHistoryManager(app, appDirectory = record.directory)) }
                }
            } catch (e: Exception) {
                failure = e.message ?: "Operation failed"
            } finally {
                lease.close()
                withContext(Dispatchers.IO) { runCatching(cleanup) }
                if (replacement) SubappSessionRegistry.resume(record.id)
                val finished = if (failure == null && completion != null) listOf(completion.copy(id = ++sequence)) else emptyList()
                value.update { it.copy(busy = false, message = failure ?: "", error = failure != null, completions = it.completions + finished) }
            }
        }
    }

    fun change(record: SubappRecord, label: String, completion: Completion? = null, change: (WorkspaceManager) -> Boolean) =
        execute(record, label, replacement = false, completion = completion) { manager, history ->
            check(history.saveSnapshot(manager, "Before $label") != null) { "Could not save version history" }
            check(change(manager)) { "Could not save workspace change" }
            check(history.saveSnapshot(manager, label) != null) { "Change saved, but version history could not be saved" }
        }

    private fun discard(cleanup: () -> Unit) { scope.launch(Dispatchers.IO) { runCatching(cleanup) } }

    private companion object {
        /** Not the screen's or the ViewModel's scope: an operation that has begun must finish and recover. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
