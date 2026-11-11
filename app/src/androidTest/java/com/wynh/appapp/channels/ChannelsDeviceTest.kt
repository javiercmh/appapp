package com.wynh.appapp.channels

import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Save as against disposable starter subapps, in both runtime modes, with the real system picker.
 * Run one emulator test at a time; screenshots go to /sdcard/Download/appapp-channels-*.png and are
 * taken while the system UI is visible.
 */
@RunWith(AndroidJUnit4::class)
class ChannelsDeviceTest : ChannelsDeviceBase() {

    @Test fun saveAsWritesOnlyAfterTheRealPickerAndDistinguishesCancellation() {
        val record = newSubapp()
        File(record.workspaceDir, "data").mkdirs()
        File(record.workspaceDir, "data/blob.bin").writeBytes(ByteArray(70_000) { (it % 256).toByte() })
        for ((mode, intent) in modes(record).withIndex()) withPage(intent) { _, web ->
            assertEquals("{\"available\":true,\"file\":true}", call(web, "api.save.availability()"))
            assertEquals("{\"error\":\"invalid_arguments\"}", call(web, "api.save.text({name:'a',mimeType:'application/pdf',content:'x'})"))
            assertEquals("{\"error\":\"not_found\"}", call(web, "api.save.file({path:'data/missing.bin'})"))
            // Saved: the real create-document picker, then the bytes read back from shared storage.
            val name = "appapp-save-$mode.bin"
            shell("rm -f /sdcard/Download/$name")
            start(web, "api.save.file({path:'data/blob.bin',name:'$name'})")
            awaitSystemWindow("documentsui", web)
            clickPickerSave(web, beforeClick = { screenshot("save-picker-$mode") })
            assertEquals("true", await(web, "JSON.parse(window.result).saved === true"))
            assertArrayEquals(File(record.workspaceDir, "data/blob.bin").readBytes(), shell("cat /sdcard/Download/$name"))
            // Cancelled is not saved and not an error: back out of the real picker.
            start(web, "api.save.text({name:'d',mimeType:'text/plain',content:'x'})")
            awaitSystemWindow("documentsui", web)
            screenshot("save-picker-cancel-$mode")
            shell("input keyevent KEYCODE_BACK")
            assertEquals("{\"saved\":false,\"cancelled\":true}", unquote(await(web, "window.result")))
            // No temporary data remains once both operations finished.
            awaitUntil(what = "staging removed") { File(context.cacheDir, "saveas").walkTopDown().none { it.isFile } }
        }
    }

    @Test fun savedBytesReachTheDestinationAfterTheHostIsRecreatedWhileThePickerIsOpen() {
        val record = newSubapp()
        for ((mode, intent) in modes(record).withIndex()) withPage(intent) { scenario, web ->
            val name = "appapp-rotation-$mode.txt"
            val content = "rotation 日本語 $mode"
            shell("rm -f /sdcard/Download/$name")
            start(web, "api.save.text({name:'$name',mimeType:'text/plain',content:'$content'})")
            awaitSystemWindow("documentsui", web)
            lateinit var host: AppCompatActivity
            scenario.onActivity { host = it }
            instrumentation.runOnMainSync { host.recreate() }
            awaitSystemWindow("documentsui", null)
            // The restored saved state still owns the request: choosing a destination writes the staged bytes.
            clickPickerSave(null, beforeClick = { screenshot("save-rotation-picker-$mode") })
            awaitUntil(30, "destination holds the saved bytes") { shell("cat /sdcard/Download/$name").contentEquals(content.toByteArray()) }
            // The old page is gone and gets no reply; nothing is left staged, and a new save can start.
            awaitUntil(what = "staging removed") { File(context.cacheDir, "saveas").walkTopDown().none { it.isFile } }
            lateinit var fresh: WebView
            awaitUntil(what = "recreated page") { var ok = false; scenario.onActivity { fresh = webOf(it.findViewById(android.R.id.content))!!; ok = true }; ok }
            initialize(fresh)
            start(fresh, "api.save.text({name:'again.txt',mimeType:'text/plain',content:'x'})")
            awaitSystemWindow("documentsui", fresh)
            shell("input keyevent KEYCODE_BACK")
            assertEquals("{\"saved\":false,\"cancelled\":true}", unquote(await(fresh, "window.result")))
        }
    }
}
