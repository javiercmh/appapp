package com.wynh.appapp.channels

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wynh.appapp.workspace.WorkspaceFiles
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A subapp's own `<input type="file">` through the existing RuntimeFileChooser and the real system
 * document picker, in the editor and in the standalone runtime. A repository-owned disposable JSON
 * file and a 256-byte binary file are created in shared Downloads by the test; the test taps the
 * page's real button, picks the file by its fixture name, and checks the exact bytes the page read.
 * Choosing a file must not write to the workspace. Run one emulator test at a time.
 */
@RunWith(AndroidJUnit4::class)
class FileInputDeviceTest : ChannelsDeviceBase() {
    private val inserted = mutableListOf<Uri>()
    @After fun removeFixtures() { inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } } }

    private val page = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <button id="open-json" type="button" style="display:block;width:100%;height:160px;font-size:40px">json</button>
        <button id="open-binary" type="button" style="display:block;width:100%;height:160px;font-size:40px">binary</button>
        <input id="json-input" type="file" accept="application/json,.json" hidden>
        <input id="binary-input" type="file" hidden>
        <script>
        window.fileResult = null;
        function wire(button, input, key) {
          const field = document.getElementById(input);
          document.getElementById(button).addEventListener('click', () => { field.value = ''; field.click(); });
          field.addEventListener('change', async () => {
            const file = field.files && field.files[0];
            if (!file) return;
            const bytes = new Uint8Array(await file.arrayBuffer());
            window.fileResult = {key, name: file.name, size: file.size,
              text: key === 'json' ? await file.text() : null,
              hex: Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')};
          });
        }
        wire('open-json', 'json-input', 'json'); wire('open-binary', 'binary-input', 'binary');
        </script>""".trimIndent()

    private fun download(name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Could not create $name" }
        inserted += uri
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
    }

    /** Every workspace file except the log, with its size and modification time. */
    private fun snapshot(root: File) = WorkspaceFiles.regularFiles(root).filter { it.name != "app.log" }
        .associate { it.relativeTo(root).invariantSeparatorsPath to (it.length() to it.lastModified()) }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun literal(value: String) = JSONObject.quote(value)

    @Test fun aPageFileInputReadsTheChosenFileExactlyAndCancellationChangesNothing() {
        val record = newSubapp()
        WorkspaceFiles(record.workspaceDir).writeText("index.html", page)
        val before = snapshot(record.workspaceDir)
        val json = """{"title":"Story 日本語 ☃","chapters":[1,2,3],"nested":{"a":"é"}}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val binary = ByteArray(256) { it.toByte() }
        for ((mode, intent) in modes(record).withIndex()) {
            val jsonName = "appapp-open-fixture-$mode.json"
            val binaryName = "appapp-open-fixture-$mode.bin"
            download(jsonName, "application/json", jsonBytes)
            download(binaryName, "application/octet-stream", binary)
            withPage(intent) { scenario, web ->
                var editor = false
                scenario.onActivity { editor = it is com.wynh.appapp.MainActivity }
                if (editor) {
                    js(web, "window.previousTestDocument = true")
                    scenario.onActivity { it.findViewById<android.view.View>(com.wynh.appapp.R.id.hub_btn_run).performClick() }
                }
                await(web, "!window.previousTestDocument && document.readyState === 'complete' && !!document.getElementById('open-json')")
                // A real touch on the page's button opens the picker (a user gesture is required).
                tapElement(web, "open-json")
                awaitSystemWindow("documentsui", web)
                clickPickerFile(jsonName, web, beforeClick = { screenshot("file-input-json-$mode") })
                assertEquals("true", await(web, "window.fileResult && window.fileResult.key === 'json' && window.fileResult.name === ${literal(jsonName)}"))
                assertEquals("File.text() is exactly the fixture", "true", await(web, "window.fileResult.text === ${literal(json)}"))
                assertEquals("true", await(web, "window.fileResult.size === ${jsonBytes.size} && window.fileResult.hex === '${hex(jsonBytes)}'"))
                // Binary data through arrayBuffer(), every byte value.
                js(web, "window.fileResult = null")
                tapElement(web, "open-binary")
                awaitSystemWindow("documentsui", web)
                clickPickerFile(binaryName, web)
                assertEquals("true", await(web, "window.fileResult && window.fileResult.key === 'binary' && window.fileResult.size === 256 && window.fileResult.hex === '${hex(binary)}'"))
                // Cancellation: no file, no result, nothing written.
                js(web, "window.fileResult = null")
                tapElement(web, "open-json")
                awaitSystemWindow("documentsui", web)
                screenshot("file-input-cancel-$mode")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                awaitUntil(what = "page back on screen after the picker closed") { !windowPackage().contains("documentsui") && windowPackage().isNotEmpty() }
                assertEquals("true", await(web, "document.getElementById('json-input').files.length === 0 && window.fileResult === null"))
            }
            // Choosing and cancelling never write to the workspace; only a page's own writeFile would.
            assertEquals("no workspace file changed in mode $mode", before, snapshot(record.workspaceDir))
        }
    }
}
