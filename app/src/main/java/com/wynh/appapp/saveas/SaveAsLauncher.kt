package com.wynh.appapp.saveas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface SaveOutcome {
    data class Saved(val name: String) : SaveOutcome
    data object Cancelled : SaveOutcome
    data object Failed : SaveOutcome
}

/** Hands a finished save to the session still waiting for it; nothing waits after rotation or disposal. */
object SaveAsLedger {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<SaveOutcome>>()
    fun expect(requestId: String) = CompletableDeferred<SaveOutcome>().also { waiting[requestId] = it }
    fun forget(requestId: String) { waiting.remove(requestId) }
    fun complete(requestId: String, outcome: SaveOutcome) { waiting.remove(requestId)?.complete(outcome) }
}

object SaveAsStagingRoot {
    fun of(context: Context) = SaveAsStaging.shared(File(context.cacheDir, "saveas"))
}

/**
 * Writes staged bytes to the destination the user chose. It runs in an application-lifetime scope,
 * so rotation and disposal of a screen cannot cancel it; process death can, which leaves the
 * `writing` marker and is recovered as an unknown, discarded outcome. A destination is only ever
 * written from the picker result's own grant, never from a persisted URI.
 */
object SaveAsWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)

    fun finish(context: Context, subappId: String, requestId: String, name: String, uri: Uri?) {
        val app = context.applicationContext
        val staging = SaveAsStagingRoot.of(app)
        if (uri == null) {
            scope.launch { staging.discard(subappId, requestId); SaveAsLedger.complete(requestId, SaveOutcome.Cancelled) }
            return
        }
        scope.launch {
            val outcome = try {
                permits.withPermit {
                    // A request staged by an earlier process (restored picker) is adopted so no sweep removes it mid-write.
                    if (!staging.adopt(subappId, requestId) || !staging.exists(subappId, requestId)) return@withPermit SaveOutcome.Failed
                    staging.markWriting(subappId, requestId)
                    // Some document providers reject "wt"; the picker's document is new and empty, so "w" is equivalent there.
                    val output = (try { app.contentResolver.openOutputStream(uri, "wt") }
                        catch (_: java.io.FileNotFoundException) { null } catch (_: IllegalArgumentException) { null } catch (_: UnsupportedOperationException) { null })
                        ?: app.contentResolver.openOutputStream(uri, "w") ?: throw IOException("No output stream")
                    // use closes the stream last; a failed close is a failed save.
                    output.use { out -> staging.data(subappId, requestId).inputStream().use { it.copyTo(out) }; out.flush() }
                    SaveOutcome.Saved(name)
                }
            } catch (_: Exception) { SaveOutcome.Failed }
            withContext(NonCancellable) { staging.discard(subappId, requestId) }
            SaveAsLedger.complete(requestId, outcome)
        }
    }
}

/** Activity-owned picker state: it survives rotation and process death through saved state. */
class SaveAsLauncher(private val activity: ComponentActivity) {
    private class Pending(val subappId: String, val requestId: String, val name: String)
    private var pending: Pending? = null
    private var preparing: Any? = null

    private class Request(val name: String, val mimeType: String)
    private val launcher = activity.registerForActivityResult(object : ActivityResultContract<Request, Uri?>() {
        override fun createIntent(context: Context, input: Request) = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mimeType).putExtra(Intent.EXTRA_TITLE, input.name)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }) { uri ->
        val request = pending
        pending = null
        if (request != null) SaveAsWriter.finish(activity, request.subappId, request.requestId, request.name, uri)
    }

    init {
        activity.savedStateRegistry.registerSavedStateProvider("app2.saveas") {
            Bundle().apply { pending?.let { putStringArray("pending", arrayOf(it.subappId, it.requestId, it.name)) } }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val saved = activity.savedStateRegistry.consumeRestoredStateForKey("app2.saveas")?.getStringArray("pending")
                pending = saved?.takeIf { it.size == 3 && SaveAsStaging.valid(it[0]) && SaveAsStaging.valid(it[1]) }?.let { Pending(it[0], it[1], it[2]) }
            }
        })
    }

    internal val pickerOpen get() = pending != null
    fun acquire(owner: Any): Boolean { if (pending != null || preparing != null) return false; preparing = owner; return true }
    fun release(owner: Any) { if (preparing === owner) preparing = null }
    fun launch(owner: Any, subappId: String, requestId: String, name: String, mimeType: String) {
        check(preparing === owner && pending == null)
        pending = Pending(subappId, requestId, name)
        preparing = null
        try { launcher.launch(Request(name, mimeType)) } catch (error: Exception) { pending = null; throw error }
    }
}
