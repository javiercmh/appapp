package com.wynh.appapp.marketplace

import com.google.gson.JsonParser
import com.wynh.appapp.updates.GitHubSource
import com.wynh.appapp.updates.GitHubSubappInstaller
import com.wynh.appapp.updates.InstalledUpdate
import com.wynh.appapp.workspace.SubappRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MarketplaceCatalogTest {
    @get:Rule val temp = TemporaryFolder()

    private class Response(
        url: URL,
        private val body: ByteArray,
        private val status: Int = 200,
    ) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = body.inputStream()
        override fun getErrorStream() = if (status >= 400) body.inputStream() else null
    }

    private fun bundledCatalogFile(): File {
        val candidates = listOf(
            File("src/main/assets/catalog/catalog.json"),
            File("app/src/main/assets/catalog/catalog.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate bundled catalog.json from ${File(".").absolutePath}")
    }

    @Test
    fun parsesBundledCatalogJson() {
        val raw = bundledCatalogFile().readText(Charsets.UTF_8)
        val catalog = MarketplaceCatalogParser.parse(raw)
        assertEquals(1, catalog.schemaVersion)
        assertEquals(listOf("lingomage", "working-hours", "b-log", "starter-demo"), catalog.apps.map { it.id })

        val lingo = catalog.apps[0]
        assertEquals("LingoMage", lingo.name)
        assertEquals("Javier Melo", lingo.author)
        assertFalse(lingo.offlineOnly)
        assertEquals(listOf(CatalogCapability.AI, CatalogCapability.SPEECH), lingo.capabilities)
        val lingoSrc = lingo.source as CatalogSource.GitHub
        assertEquals(GitHubSource("javiercmh", "lingomage", "main"), lingoSrc.source)
        assertEquals("com.wynh.lingomage", lingoSrc.publisherAppId)

        val hours = catalog.apps[1]
        assertEquals("Javier Melo", hours.author)
        assertTrue(hours.offlineOnly)
        assertEquals(listOf(CatalogCapability.REMINDERS), hours.capabilities)
        val hoursSrc = hours.source as CatalogSource.GitHub
        assertEquals(GitHubSource("javiercmh", "working-hours", "main"), hoursSrc.source)
        assertEquals("com.wynh.workinghours", hoursSrc.publisherAppId)

        val bLog = catalog.apps[2]
        assertEquals("B-log", bLog.name)
        assertEquals("Javier Melo", bLog.author)
        assertEquals("Sports", bLog.category)
        assertTrue(bLog.offlineOnly)
        assertEquals(listOf(CatalogCapability.FILES, CatalogCapability.SHARE), bLog.capabilities)
        assertEquals(1L, bLog.minHostCompatibility)
        assertEquals("b_log", bLog.iconKey)
        val bLogSrc = bLog.source as CatalogSource.GitHub
        assertEquals(GitHubSource("javiercmh", "b-log", "main"), bLogSrc.source)
        assertEquals("com.wynh.blog", bLogSrc.publisherAppId)

        val starter = catalog.apps[3]
        assertEquals("App\u00b2", starter.author)
        assertTrue(starter.offlineOnly)
        assertEquals(CatalogSource.BundledStarter, starter.source)
    }

    @Test
    fun manifestAuthorIsReadOnlyFromAnExplicitAuthorField() {
        fun author(json: String) = ManifestAuthorResolver.fromManifestJson(JsonParser.parseString(json).asJsonObject)

        assertEquals("Alice", author("""{"author":"Alice","app2Update":{"appId":"com.bob.app"}}"""))
        assertEquals("Alice", author("""{"author":{"name":" Alice "}}"""))
        // A publisher app ID is not a name; nothing is guessed from it.
        assertNull(author("""{"name":"LingoMage","app2Update":{"appId":"com.wynh.lingomage"}}"""))
        assertNull(author("""{"author":"   "}"""))
        assertNull(author("""{"author":42}"""))
        assertEquals(60, author("""{"author":"${"a".repeat(200)}"}""")!!.length)
        assertNull(ManifestAuthorResolver.fromManifestJson(null))
    }

    @Test
    fun rejectsMalformedOrUnsafeCatalogAndIgnoresUnknownCapabilities() {
        assertThrows(IllegalArgumentException::class.java) { MarketplaceCatalogParser.parse("not-json") }
        assertThrows(IllegalArgumentException::class.java) {
            MarketplaceCatalogParser.parse("""{"schemaVersion":2,"apps":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MarketplaceCatalogParser.parse("""{"schemaVersion":1,"apps":[]}""")
        }

        val duplicateIds = """
            {
              "schemaVersion": 1,
              "apps": [
                {"id":"app-one","name":"One","author":"a","category":"Cat","license":"MIT","summary":"s","description":"d","capabilities":[],"offlineOnly":true,"minHostCompatibility":1,"iconKey":"default","source":{"type":"bundled_starter"}},
                {"id":"app-one","name":"Two","author":"a","category":"Cat","license":"MIT","summary":"s","description":"d","capabilities":[],"offlineOnly":true,"minHostCompatibility":1,"iconKey":"default","source":{"type":"bundled_starter"}}
              ]
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { MarketplaceCatalogParser.parse(duplicateIds) }

        val badRepo = """
            {
              "schemaVersion": 1,
              "apps": [
                {"id":"bad","name":"Bad","author":"a","category":"Cat","license":"MIT","summary":"s","description":"d","capabilities":[],"offlineOnly":true,"minHostCompatibility":1,"iconKey":"default","source":{"type":"github","owner":"bad/owner","repository":"repo","branch":"main"}}
              ]
            }
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { MarketplaceCatalogParser.parse(badRepo) }

        val unknownCap = """
            {
              "schemaVersion": 1,
              "apps": [
                {"id":"future-app","name":"Future","author":"a","category":"Cat","license":"MIT","summary":"s","description":"d","capabilities":["ai","unknown_future_cap"],"offlineOnly":false,"minHostCompatibility":1,"iconKey":"default","source":{"type":"bundled_starter"}}
              ]
            }
        """.trimIndent()
        val parsed = MarketplaceCatalogParser.parse(unknownCap)
        assertEquals(listOf(CatalogCapability.AI), parsed.apps.single().capabilities)
    }

    @Test
    fun repositoryLoadsOfflineCachesManualRefreshAndInvalidatesOnApkUpgrade() {
        val bundledRaw = bundledCatalogFile().readText(Charsets.UTF_8)
        val cacheRoot = temp.newFolder("marketplace")
        var networkCalls = 0
        val remoteCatalog = """
            {
              "schemaVersion": 1,
              "apps": [
                {
                  "id": "custom-remote",
                  "name": "Custom Remote",
                  "category": "Tools",
                  "license": "MIT",
                  "summary": "Remote entry",
                  "description": "Fetched via manual refresh",
                  "capabilities": ["files"],
                  "offlineOnly": true,
                  "minHostCompatibility": 1,
                  "iconKey": "custom_icon",
                  "source": {"type": "github", "owner": "javiercmh", "repository": "custom-remote", "branch": "main"}
                },
                {
                  "id": "starter-demo",
                  "name": "Starter Demo",
                  "category": "Template",
                  "license": "MIT",
                  "summary": "Editable starter",
                  "description": "Starter",
                  "capabilities": ["template"],
                  "offlineOnly": true,
                  "minHostCompatibility": 1,
                  "iconKey": "starter_demo",
                  "source": {"type": "bundled_starter"}
                }
              ]
            }
        """.trimIndent()

        var serveCorrupt = false
        val catalogUrl = MarketplaceCatalogRepository.DEFAULT_CATALOG_URL
        val fakeConnect: (URL) -> HttpURLConnection = { url ->
            networkCalls++
            when (url.toString()) {
                catalogUrl -> {
                    val body = if (serveCorrupt) "{broken".toByteArray() else remoteCatalog.toByteArray()
                    Response(url, body)
                }
                "https://raw.githubusercontent.com/javiercmh/custom-remote/main/icon.png" ->
                    Response(url, byteArrayOf(1, 2, 3, 4))
                else -> Response(url, byteArrayOf(), 404)
            }
        }

        val repoV1 = MarketplaceCatalogRepository(
            cacheRoot = cacheRoot,
            bundledVersionCode = 18,
            readBundled = { bundledRaw },
            connect = fakeConnect,
            catalogUrl = catalogUrl,
            clock = { 12345L },
        )

        val initial = repoV1.loadLocal()
        assertEquals(0, networkCalls)
        assertFalse(initial.syncedFromRemote)
        assertEquals(4, initial.apps.size)

        val refreshed = repoV1.refreshFromRemote()
        assertTrue(refreshed.syncedFromRemote)
        assertEquals(12345L, refreshed.syncedAtMillis)
        assertEquals(listOf("custom-remote", "starter-demo"), refreshed.apps.map { it.id })
        assertNull(refreshed.apps[0].author)
        assertTrue(refreshed.iconFiles["custom-remote"]?.isFile == true)
        // One catalog request plus one icon for the entry without a built-in icon; no manifests.
        assertEquals(2, networkCalls)

        val cachedLoad = repoV1.loadLocal()
        assertEquals(2, networkCalls)
        assertTrue(cachedLoad.syncedFromRemote)
        assertEquals(listOf("custom-remote", "starter-demo"), cachedLoad.apps.map { it.id })
        assertTrue(cachedLoad.iconFiles["custom-remote"]?.isFile == true)

        // Failed remote refresh must leave the previous valid cache intact.
        serveCorrupt = true
        assertThrows(IllegalArgumentException::class.java) { repoV1.refreshFromRemote() }
        assertEquals(listOf("custom-remote", "starter-demo"), repoV1.loadLocal().apps.map { it.id })

        // Advancing bundledVersionCode invalidates the cache so new APK entries take precedence.
        val repoV2 = MarketplaceCatalogRepository(
            cacheRoot = cacheRoot,
            bundledVersionCode = 19,
            readBundled = { bundledRaw },
            connect = fakeConnect,
            catalogUrl = catalogUrl,
        )
        val afterUpgrade = repoV2.loadLocal()
        assertFalse(afterUpgrade.syncedFromRemote)
        assertEquals(listOf("lingomage", "working-hours", "b-log", "starter-demo"), afterUpgrade.apps.map { it.id })
    }

    @Test
    fun matcherUsesOnlyRecordedProvenanceAndCatalogAuthor() {
        val catalog = MarketplaceCatalogParser.parse(bundledCatalogFile().readText(Charsets.UTF_8))
        val unattributed = MarketplaceApp(
            id = "unattributed",
            name = "Unattributed",
            author = null,
            category = "Tools",
            license = "MIT",
            summary = "No catalog author",
            description = "Falls back to the installed manifest author",
            capabilities = emptyList(),
            offlineOnly = true,
            minHostCompatibility = 1L,
            iconKey = "default",
            source = CatalogSource.GitHub(GitHubSource("someone", "unattributed", "main"), null),
        )
        val apps = catalog.apps + unattributed + MarketplaceApp(
            id = "future-host-app",
            name = "Future Host App",
            author = "javiercmh",
            category = "Tools",
            license = "MIT",
            summary = "Needs newer host",
            description = "Needs host level 99",
            capabilities = emptyList(),
            offlineOnly = true,
            minHostCompatibility = 99L,
            iconKey = "default",
            source = CatalogSource.BundledStarter,
        )

        // 1. LingoMage installed from a fork, matched via publisherAppId in update-state.json
        val lingoRec = makeRecord("lingo-1")
        File(lingoRec.directory, "update-state.json").writeText(
            GitHubSubappInstaller.serializeUpdateState(
                InstalledUpdate(
                    appId = "com.wynh.lingomage",
                    version = "1.4.2",
                    source = GitHubSource("some-fork", "lingomage-custom", "dev"),
                    commit = "a".repeat(40),
                    hashes = emptyMap(),
                    seeds = emptySet(),
                )
            )
        )

        // 2. Working Hours imported via GitHub URL, matched via normalized URL in import-source.json
        val whRec = makeRecord("wh-1")
        File(whRec.directory, "import-source.json").writeText(
            """{"url":"https://github.com/Javiercmh/working-hours.git","commit":"${"b".repeat(40)}"}"""
        )

        // 3. An app the user built from the starter: it keeps the starter's files and short name but
        //    has no recorded provenance, so it must never count as a Starter Demo copy.
        val ownApp = makeRecord("own-app")
        File(ownApp.workspaceDir, "manifest.json").writeText("""{"name":"My Notes","short_name":"Demo"}""")
        File(ownApp.workspaceDir, "js").mkdirs()
        File(ownApp.workspaceDir, "js/ai-demo.js").writeText("")
        File(ownApp.workspaceDir, "data").mkdirs()
        File(ownApp.workspaceDir, "data/entries.json").writeText("[]")

        // 4. Starter Demo installed from Discover with catalogId
        val starterCopy = makeRecord("starter-copy")
        File(starterCopy.directory, "import-source.json").writeText("""{"catalogId":"starter-demo"}""")

        // 5. Imported app whose manifest names an author the catalog entry lacks
        val unattributedRec = makeRecord("unattributed-1")
        File(unattributedRec.directory, "import-source.json").writeText("""{"url":"https://github.com/someone/unattributed"}""")
        File(unattributedRec.workspaceDir, "manifest.json").writeText("""{"name":"Unattributed","author":"Someone"}""")

        // 6. B-log matched via publisherAppId in manifest.json
        val bLogRec = makeRecord("b-log-1")
        File(bLogRec.workspaceDir, "manifest.json").writeText(
            """{"name":"B-log","version":"0.8.1","app2Update":{"appId":"com.wynh.blog"}}"""
        )

        val records = listOf(lingoRec, whRec, ownApp, starterCopy, unattributedRec, bLogRec)
        val provenance = records.associate { it.id to MarketplaceMatcher.inspectInstalled(it) }

        val matched = MarketplaceMatcher.match(
            apps = apps,
            records = records,
            provenance = provenance,
            updateAvailable = { it.id == lingoRec.id },
            hostLevel = 11L,
        )

        val lingoState = matched.first { it.app.id == "lingomage" }
        assertEquals(listOf(lingoRec), lingoState.installedRecords)
        assertEquals(lingoRec, lingoState.updateRecord)
        assertTrue(lingoState.hostCompatible)
        assertEquals("Javier Melo", lingoState.resolvedAuthor)
        assertEquals("1.4.2", lingoState.installedVersion)

        val whState = matched.first { it.app.id == "working-hours" }
        assertEquals(listOf(whRec), whState.installedRecords)
        assertNull(whState.updateRecord)
        assertEquals("Javier Melo", whState.resolvedAuthor)

        val bLogState = matched.first { it.app.id == "b-log" }
        assertEquals(listOf(bLogRec), bLogState.installedRecords)
        assertNull(bLogState.updateRecord)
        assertTrue(bLogState.hostCompatible)
        assertEquals("Javier Melo", bLogState.resolvedAuthor)
        assertEquals("0.8.1", bLogState.installedVersion)

        val starterState = matched.first { it.app.id == "starter-demo" }
        assertEquals(listOf(starterCopy), starterState.installedRecords)
        assertNull(starterState.updateRecord)
        assertTrue(matched.none { state -> ownApp in state.installedRecords })

        val unattributedState = matched.first { it.app.id == "unattributed" }
        assertEquals(listOf(unattributedRec), unattributedState.installedRecords)
        assertEquals("Someone", unattributedState.resolvedAuthor)

        val futureState = matched.first { it.app.id == "future-host-app" }
        assertFalse(futureState.hostCompatible)
        assertTrue(futureState.installedRecords.isEmpty())
    }

    private fun makeRecord(name: String): SubappRecord {
        val dir = temp.newFolder(name)
        val workspace = File(dir, "workspace").apply { mkdirs() }
        File(workspace, "index.html").writeText("<html></html>")
        return SubappRecord(name, dir)
    }
}
