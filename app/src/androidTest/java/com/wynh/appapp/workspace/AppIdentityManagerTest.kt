package com.wynh.appapp.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIdentityManagerTest {
    @Test fun configurationKeepsManifestFieldsStagesIconsAndResetsOnlyIconAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SubappRepository(context)
        val record = repository.createStarter()
        val workspace = WorkspaceManager(context, workspaceDir = record.workspaceDir)
        val manager = AppIdentityManager(context, record)
        try {
            val manifest = JSONObject(workspace.readFile("manifest.json"))
                .put("name", "Before")
                .put("description", "Old description")
                .put("short_name", "Launcher label")
                .put("publisherField", "preserve me")
                .put("icons", JSONArray().put(JSONObject().put("src", "assets/icons/original.png").put("sizes", "128x128")))
                .put("files", JSONArray().put("index.html").put("assets/icons/original.png"))
            assertTrue(workspace.writeFile("manifest.json", manifest.toString(2)))
            assertTrue(workspace.writeFile("assets/icons/original.png", "original icon"))

            assertTrue(manager.save("Renamed", "New description", AppIdentityManager.IconChange.Keep))
            var saved = JSONObject(workspace.readFile("manifest.json"))
            assertEquals("Launcher label", saved.getString("short_name"))
            assertEquals("preserve me", saved.getString("publisherField"))
            assertEquals("assets/icons/original.png", saved.getJSONArray("icons").getJSONObject(0).getString("src"))
            assertEquals("original icon", workspace.readFile("assets/icons/original.png"))

            val replacement = byteArrayOf(1, 2, 3, 4)
            assertTrue(manager.save("Renamed", "", AppIdentityManager.IconChange.Replace(replacement)))
            assertArrayEquals(replacement, workspace.getFile("icon.png").readBytes())
            saved = JSONObject(workspace.readFile("manifest.json"))
            assertEquals("icon.png", saved.getJSONArray("icons").getJSONObject(0).getString("src"))
            assertTrue((0 until saved.getJSONArray("files").length()).any { saved.getJSONArray("files").getString(it) == "icon.png" })

            assertTrue(manager.save("Renamed", "", AppIdentityManager.IconChange.Reset))
            saved = JSONObject(workspace.readFile("manifest.json"))
            assertFalse(saved.has("icons"))
            assertFalse((0 until saved.getJSONArray("files").length()).any { saved.getJSONArray("files").getString(it) == "icon.png" })
            assertFalse(workspace.getFile("icon.png").exists())
            assertTrue(workspace.getFile("assets/icons/original.png").exists())
        } finally {
            repository.delete(record.id)
        }
    }
}
