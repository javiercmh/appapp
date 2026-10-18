package com.wynh.appapp.runtime

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Shared picker semantics, with exactly one completion for every accepted callback. */
class RuntimeFileChooser(activity: ComponentActivity, private val log: (String) -> Unit) {
    private var pending: ValueCallback<Array<Uri>>? = null
    private val single = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        complete(uri?.let { arrayOf(it) })
    }
    private val multiple = activity.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        complete(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
    fun open(callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
        if (callback == null) return false
        complete(null)
        pending = callback
        try {
            val mime = mimeType(params?.acceptTypes)
            if (params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) multiple.launch(mime) else single.launch(mime)
        } catch (e: Exception) { log("[WebView Error] Could not open file picker: ${e.message}"); complete(null) }
        return true
    }
    fun complete(uris: Array<Uri>?) {
        val callback = pending ?: return
        pending = null
        runCatching { callback.onReceiveValue(uris) }.onFailure { log("[WebView] File picker page closed") }
    }
    companion object {
        fun mimeType(types: Array<String>?): String {
            val mimes = types.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.map {
                if (it.startsWith('.')) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1).lowercase()) ?: "*/*" else it
            }
            if (mimes.isEmpty()) return "*/*"
            val family = mimes.first().substringBefore('/')
            return if (mimes.size == 1) mimes.first() else if (mimes.all { it.substringBefore('/') == family }) "$family/*" else "*/*"
        }
    }
}
