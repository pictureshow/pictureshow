/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OFFLINE_PART_SUFFIX
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.offlineDecodeManifest
import app.pictureshow.core.offline.offlineEncodeManifest
import app.pictureshow.core.offline.offlineEntryId
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class OfflineEngineTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val media = ByteArray(900_000) { (it % 241).toByte() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val deviceFor = ConcurrentHashMap<String, String>()
    private val scopes = mutableListOf<CoroutineScope>()
    private var free: Long? = 10L * 1024 * 1024 * 1024
    private var network = OfflineNetwork.unmetered
    private val foreground = CopyOnWriteArrayList<List<String>>()
    private var cutAt: Int? = null
    private var hold = false
    private var slow = false
    private var quickConnect = true
    private var logoutStatus = 204

    @Volatile
    private var serverOff = false
    private var restMs = 1L
    private var recheckMs = 30_000L
    private var revocationMs = 60_000L
    private val unfinishedReports = java.util.concurrent.CopyOnWriteArrayList<Boolean>()

    @Volatile
    private var offAfterNextCut = false

    @Volatile
    private var portalAfterNextCut = false

    @Volatile
    private var portal = false

    @Volatile
    private var networkThrows = false

    @Volatile
    private var freeThrows = false

    @Volatile
    private var downloadsAlwaysDrop = false

    @Volatile
    private var holdItems = false
    private var convertedBody: ByteArray? = null
    private var afterConversion: ByteArray? = null
    private var httpOverride: OfflineHttp? = null
    private var apiClientOverride: okhttp3.OkHttpClient? = null
    private var restartTrust: (Long) -> Long = { it }
    private var cleaner: (OfflineFiles) -> Unit = OfflineFiles::emptyTrash

    private lateinit var serverInfo: Server
    private lateinit var viewerA: Account
    private lateinit var viewerB: Account
    private lateinit var accounts: AccountStore

    private fun itemJson(id: String, name: String = "Title $id", size: Long = media.size.toLong()) =
        """{"Id":"$id","Name":"$name","Type":"Movie","RunTimeTicks":100000000,"UserData":{"PlaybackPositionTicks":0,"Played":false,"PlayCount":0},
        "MediaSources":[{"Id":"s$id","ETag":"etag","Container":"mkv","Size":$size,"MediaStreams":[{"Index":0,"Type":"Video"},{"Index":1,"Type":"Audio","Language":"eng"}]}]}"""

    @Before
    fun setUp() {
        File(context.noBackupFilesDir, "offline").deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (serverOff) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                if (portal) return MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Sign in to Wi-Fi</html>")
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    holdItems && path.contains("/Items/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    path.startsWith("/Users/") && path.contains("/Items/") -> json(itemJson(path.substringAfterLast('/')))
                    path == "/QuickConnect/Enabled" -> json(if (quickConnect) "true" else "false")
                    path == "/QuickConnect/Initiate" -> json("""{"Code":"123456","Secret":"secret"}""")
                    path == "/QuickConnect/Authorize" -> {
                        // The session that authorizes decides which viewer the code belongs to.
                        val viewer = if (request.getHeader("Authorization").orEmpty().contains("token-b")) "viewer-b" else "viewer-a"
                        deviceFor["secret"] = viewer
                        json("true")
                    }
                    path == "/Users/AuthenticateWithQuickConnect" -> {
                        val viewer = deviceFor["secret"] ?: "viewer-a"
                        json("""{"AccessToken":"set-$viewer","User":{"Id":"$viewer"}}""")
                    }
                    path == "/Sessions/Logout" -> MockResponse().setResponseCode(logoutStatus)
                    path.endsWith("/Download") -> download(request)
                    path.contains("/stream.") -> conversion()
                    path.contains("/Images/") -> MockResponse().setResponseCode(404)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        serverInfo = Server("server", "Server", server.url("/").toString().trimEnd('/'), "12.4")
        viewerA = Account(serverInfo, User("viewer-a", "A"), "token-a")
        viewerB = Account(serverInfo, User("viewer-b", "B"), "token-b")
        accounts = AccountStore(
            object : TextPersistence {
                private var text: String? = null
                override suspend fun read() = text
                override suspend fun write(value: String) {
                    text = value
                }
            }
        )
    }

    private var restoreCrashHandler: () -> Unit = {}

    @After
    fun tearDown() {
        restoreCrashHandler()
        hold = false
        scopes.forEach { it.cancel() }
        server.shutdown()
        File(context.noBackupFilesDir, "offline").deleteRecursively()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** A conversion is chunked: there's no length to say that it stopped early. */
    private fun conversion(): MockResponse {
        val body = convertedBody ?: return MockResponse().setResponseCode(404)
        afterConversion?.let {
            convertedBody = it
            afterConversion = null
        }
        return MockResponse().setChunkedBody(Buffer().write(body), 4096)
    }

    private fun download(request: RecordedRequest): MockResponse {
        if (downloadsAlwaysDrop) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        if (offAfterNextCut) serverOff = true
        if (portalAfterNextCut) portal = true
        if (hold) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        val range = request.getHeader("Range")
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        val headers = MockResponse().setHeader("Last-Modified", "Mon").setHeader("Accept-Ranges", "bytes")
        if (range != null) headers.setResponseCode(206).setHeader("Content-Range", "bytes $start-${media.size - 1}/${media.size}")
        val end = cutAt?.takeIf { range == null } ?: media.size
        val response = headers.setBody(Buffer().write(media, start, end - start))
        if (slow) response.throttleBody(16_384, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (end !=
            media.size
        ) {
            response.setHeader("Content-Length", media.size - start).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        return response
    }

    private fun engine(): OfflineEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return OfflineEngine(
            context,
            accounts,
            { ClientIdentity("Test", "install-id", "1") },
            object : OfflineForeground {
                override fun unfinished(any: Boolean) {
                    unfinishedReports += any
                }

                override fun update(activeTitles: List<String>) {
                    foreground += activeTitles
                }
            },
            http = httpOverride ?: offlineOkHttp(),
            apiClient = apiClientOverride ?: okhttp3.OkHttpClient(),
            networkState = {
                check(!networkThrows) { "network" }
                network
            },
            freeBytes = {
                check(!freeThrows) { "free" }
                free
            },
            quotaBytes = { 1000L * 1024 * 1024 * 1024 },
            retryDelayMs = { 1 },
            unreachableDelayMs = { restMs },
            storageRecheckMs = recheckMs,
            revocationRecheckMs = revocationMs,
            trashCleaner = cleaner,
            restartTrustedBytes = restartTrust,
            scope = scope
        )
    }

    private fun item(id: String, size: Long = media.size.toLong()) = OfflineApiItem(
        id,
        "Title $id",
        "Movie",
        userData = OfflineApiUserData(),
        sources = listOf(
            OfflineApiSource("s$id", "etag", "mkv", size, streams = listOf(OfflineApiStream(0, "Video"), OfflineApiStream(1, "Audio")))
        )
    )

    private val original = OfflinePreset.original
    private fun choice(id: String, cellular: Boolean = false) = OfflineChoice(id, original, cellular = cellular)
    private fun entryId(id: String) = offlineEntryId(id, original)

    private suspend fun OfflineEngine.awaitPhase(account: Account, id: String, phase: OfflinePhase) = withTimeout(20_000) {
        libraries.first { all -> all[account.key]?.manifest?.entries?.firstOrNull { it.id == entryId(id) }?.job?.phase == phase }
        Unit
    }

    private suspend fun signIn(vararg all: Account) = all.forEach { accounts.remember(it) }

    private suspend fun started(): OfflineEngine = engine().also { it.start().join() }

    private fun OfflineEngine.entry(account: Account, id: String) = libraries.value[account.key]?.manifest?.entries?.firstOrNull {
        it.id ==
            entryId(id)
    }

    private fun mediaFile(engine: OfflineEngine, account: Account, id: String): File {
        val entry = engine.entry(account, id)!!
        return engine.resourceFile(account.key, entry, entry.resources.first { it.id == "media" })
    }

    @Test
    fun `a download reaches Ready only when verified, with the bytes and a hash`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val file = mediaFile(engine, viewerA, "1")
        assertArrayEquals(media, file.readBytes())
        val resource = engine.entry(viewerA, "1")!!.resources.first { it.id == "media" }
        assertEquals(file.sha256Hex(), resource.sha256)
        assertEquals(media.size.toLong(), engine.libraries.value[viewerA.key]!!.manifest.budget.usedBytes)
        assertFalse("nothing partial left", file.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `transfers use the set's own device and token, never the sign-in's`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val transfers = requests.filter { it.path.orEmpty().contains("/Download") }
        assertTrue(transfers.isNotEmpty())
        transfers.forEach { request ->
            val auth = request.getHeader("Authorization").orEmpty()
            assertTrue(auth, auth.contains("set-viewer-a"))
            assertFalse(auth, auth.contains("token-a"))
            assertTrue(auth, auth.contains("pictureshow-dl-"))
        }
        val persisted = engine.files.manifest(viewerA.key).readText()
        assertFalse("no token in the manifest", persisted.contains("set-viewer-a") || persisted.contains("token-a"))
    }

    @Test
    fun `a killed process resumes with a validated range and exact bytes`() = runBlocking<Unit> {
        signIn(viewerA)
        cutAt = 400_000
        val first = started()
        first.enqueue(viewerA, item("1"), choice("1"))
        // Retries keep hitting the cut only when no range is asked; let the first attempt drop then be killed.
        withTimeout(20_000) {
            while (requests.none { it.getHeader("Range") != null } && first.entry(viewerA, "1")?.job?.phase != OfflinePhase.ready) delay(5)
        }
        cutAt = null
        scopes.last().cancel()
        val second = started()
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertArrayEquals(media, mediaFile(second, viewerA, "1").readBytes())
        assertTrue(requests.any { it.path.orEmpty().contains("/Download") && it.getHeader("Range") != null })
    }

    @Test
    fun `delete removes partials and a late success can't bring it back`() = runBlocking<Unit> {
        signIn(viewerA)
        hold = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.transferring)
        engine.delete(viewerA.key, entryId("1"))
        withTimeout(10_000) { engine.libraries.first { it[viewerA.key]!!.manifest.entries.isEmpty() } }
        hold = false
        delay(100)
        assertTrue(engine.libraries.value[viewerA.key]!!.manifest.entries.isEmpty())
        assertTrue(engine.files.inventory(viewerA.key).isEmpty())
    }

    @Test
    fun `no more than two transfer at once and the rest wait their turn`() = runBlocking<Unit> {
        signIn(viewerA)
        hold = true
        val engine = started()
        listOf("1", "2", "3").forEach { engine.enqueue(viewerA, item(it), choice(it)) }
        engine.awaitPhase(viewerA, "2", OfflinePhase.transferring)
        engine.awaitPhase(viewerA, "1", OfflinePhase.transferring)
        delay(100)
        assertEquals(OfflinePhase.queued, engine.entry(viewerA, "3")!!.job.phase)
        engine.delete(viewerA.key, entryId("1"))
        engine.awaitPhase(viewerA, "3", OfflinePhase.transferring)
    }

    @Test
    fun `low storage becomes waiting and writes nothing`() = runBlocking<Unit> {
        signIn(viewerA)
        free = 100L * 1024 * 1024
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingStorage)
        assertEquals(0L, mediaFile(engine, viewerA, "1").let { File(it.path + ".part").length() })
        free = 10L * 1024 * 1024 * 1024
        engine.refresh()
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `a metered network waits for Wi-Fi unless this job allows it`() = runBlocking<Unit> {
        signIn(viewerA)
        network = OfflineNetwork.metered
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        engine.enqueue(viewerA, item("2"), choice("2", cellular = true))
        engine.awaitPhase(viewerA, "2", OfflinePhase.ready)
        assertEquals(OfflinePhase.waitingNetwork, engine.entry(viewerA, "1")!!.job.phase)
        network = OfflineNetwork.unmetered
        engine.networkChanged()
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `lowering the cap stops a transfer before its bytes can pass it`() = runBlocking<Unit> {
        signIn(viewerA)
        slow = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        withTimeout(10_000) { while (File(mediaFile(engine, viewerA, "1").path + ".part").length() == 0L) delay(5) }
        engine.setCap(viewerA.key, 1024)
        val paused = engine.entry(viewerA, "1")!!.job
        assertEquals(OfflinePhase.paused, paused.phase)
        assertTrue(paused.pausedByCap)
        slow = false
        engine.resume(viewerA.key, entryId("1"))
        delay(300)
        assertEquals(1024L, engine.libraries.value[viewerA.key]!!.manifest.budget.capBytes)
        assertNotEquals(OfflinePhase.ready, engine.entry(viewerA, "1")!!.job.phase)
    }

    @Test
    fun `signing one viewer out removes only theirs, and revokes their set`() = runBlocking<Unit> {
        signIn(viewerA, viewerB)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        engine.enqueue(viewerB, item("2"), choice("2"))
        engine.awaitPhase(viewerB, "2", OfflinePhase.ready)
        val bytesB = mediaFile(engine, viewerB, "2")
        assertNotEquals(mediaFile(engine, viewerA, "1").parentFile, bytesB.parentFile)
        val dirA = engine.files.accountDir(viewerA.key)
        accounts.signOut(viewerA.key)
        withTimeout(10_000) { engine.libraries.first { viewerA.key !in it } }
        assertFalse(dirA.exists())
        assertTrue(bytesB.exists())
        assertArrayEquals(media, bytesB.readBytes())
        withTimeout(10_000) {
            while (requests.none {
                    it.path == "/Sessions/Logout" && it.getHeader("Authorization").orEmpty().contains("set-viewer-a")
                }
            ) {
                delay(5)
            }
        }
        assertFalse(requests.any { it.path == "/Sessions/Logout" && it.getHeader("Authorization").orEmpty().contains("set-viewer-b") })
        assertFalse(requests.any { it.path == "/Sessions/Logout" && it.getHeader("Authorization").orEmpty().contains("token-") })
    }

    @Test
    fun `bytes go at sign-out even when the server can't answer, and revocation is retried`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        logoutStatus = 500
        val dirA = engine.files.accountDir(viewerA.key)
        accounts.signOut(viewerA.key)
        withTimeout(10_000) { engine.libraries.first { viewerA.key !in it } }
        assertFalse(dirA.exists())
        withTimeout(10_000) {
            while (!engine.files.sessions().readText().contains("pending-revoke")) delay(5)
        }
        assertTrue(engine.files.sessions().readText().contains("set-viewer-a"))
        logoutStatus = 204
        engine.syncAll()
        val sessions = engine.files.sessions().readText()
        assertFalse(sessions, sessions.contains("set-viewer-a"))
    }

    @Test
    fun `downloads of an account no longer on the device are gone at next start`() = runBlocking<Unit> {
        signIn(viewerB)
        val orphan = OfflineFiles(context.noBackupFilesDir).accountDir(viewerA.key)
        orphan.mkdirs()
        File(orphan, "manifest.json").writeText("{}")
        started()
        assertFalse(orphan.exists())
    }

    @Test
    fun `a missing file turns Ready into Missing at next start, and it can come back`() = runBlocking<Unit> {
        signIn(viewerA)
        val first = started()
        first.enqueue(viewerA, item("1"), choice("1"))
        first.awaitPhase(viewerA, "1", OfflinePhase.ready)
        mediaFile(first, viewerA, "1").delete()
        scopes.last().cancel()
        val second = started()
        assertEquals(OfflinePhase.missing, second.entry(viewerA, "1")!!.job.phase)
        second.redownload(viewerA.key, entryId("1"))
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `a manifest this version can't read is kept and shown recoverable`() = runBlocking<Unit> {
        signIn(viewerA)
        val manifest = OfflineFiles(context.noBackupFilesDir).manifest(viewerA.key)
        manifest.parentFile!!.mkdirs()
        val future = """{"schema":99,"entries":[]}"""
        manifest.writeText(future)
        val engine = started()
        assertTrue(engine.libraries.value[viewerA.key]!!.recoverable)
        assertEquals(future, manifest.readText())
        assertFalse(engine.enqueue(viewerA, item("1"), choice("1")))
        assertEquals(future, manifest.readText())
    }

    @Test
    fun `Quick Connect off fails honestly asking for the password`() = runBlocking<Unit> {
        signIn(viewerA)
        quickConnect = false
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        assertEquals("needs-password", engine.entry(viewerA, "1")!!.job.failure)
        withTimeout(5_000) { engine.libraries.first { it[viewerA.key]!!.needsPassword } }
    }

    @Test
    fun `deleting the last download that needed the password stops asking for it`() = runBlocking<Unit> {
        signIn(viewerA)
        quickConnect = false
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.enqueue(viewerA, item("2"), choice("2"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        engine.awaitPhase(viewerA, "2", OfflinePhase.failed)
        withTimeout(5_000) { engine.libraries.first { it[viewerA.key]!!.needsPassword } }
        engine.delete(viewerA.key, entryId("1"))
        assertTrue(engine.libraries.value[viewerA.key]!!.needsPassword)
        engine.delete(viewerA.key, entryId("2"))
        assertFalse(engine.libraries.value[viewerA.key]!!.needsPassword)
    }

    @Test
    fun `the foreground notice follows work and ends with it`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertTrue(foreground.any { it == listOf("Title 1") })
        withTimeout(5_000) {
            while (foreground.last().isNotEmpty()) delay(5)
        }
    }

    @Test
    fun `a cold start with no network lands on Downloads only when something is ready`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        assertEquals("home", offlineColdStartDestination("home", engine, viewerA.key, OfflineNetwork.none))
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertEquals("downloads", offlineColdStartDestination("home", engine, viewerA.key, OfflineNetwork.none))
        assertEquals("home", offlineColdStartDestination("home", engine, viewerA.key, OfflineNetwork.unmetered))
        assertEquals("login", offlineColdStartDestination("login", engine, viewerA.key, OfflineNetwork.none))
    }

    @Test
    fun `a server out of reach rests between attempts however often the network reports in`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        serverOff = true
        restMs = 60_000
        requests.clear()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        delay(200)
        val tried = requests.size
        repeat(50) {
            engine.networkCapabilitiesChanged()
            delay(5)
        }
        delay(200)
        assertEquals(tried, requests.size)
        assertEquals(OfflinePhase.waitingNetwork, engine.entry(viewerA, "1")?.job?.phase)
    }

    private fun itemRequests(id: String) = requests.count { it.path.orEmpty().substringBefore('?').endsWith("/Items/$id") }

    @Test
    fun `one download's rest ending doesn't cut another's short`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("0"), choice("0"))
        engine.awaitPhase(viewerA, "0", OfflinePhase.ready)
        serverOff = true
        restMs = 60_000
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        delay(100)
        val tried = itemRequests("1")
        restMs = 50
        engine.enqueue(viewerA, item("2"), choice("2"))
        withTimeout(10_000) { while (itemRequests("2") < 3) delay(10) }
        assertEquals(tried, itemRequests("1"))
    }

    @Test
    fun `a rest ends on its own, and the next try finishes once the server is back`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        serverOff = true
        restMs = 300
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        serverOff = false
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `a server that dies mid-transfer means waiting, and the download finishes by itself when it returns`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        restMs = 200
        cutAt = 400_000
        offAfterNextCut = true
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        offAfterNextCut = false
        cutAt = null
        serverOff = false
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertArrayEquals(media, mediaFile(engine, viewerA, "1").readBytes())
    }

    @Test
    fun `a server that answers while its transfers keep dropping ends in failed with Retry`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        downloadsAlwaysDrop = true
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        assertEquals("network", engine.entry(viewerA, "1")?.job?.failure)
    }

    @Test
    fun `a sign-in page answering in place of the server means waiting, with the partial kept`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        restMs = 200
        cutAt = 400_000
        portalAfterNextCut = true
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        val part = File(mediaFile(engine, viewerA, "1").path + OFFLINE_PART_SUFFIX)
        assertArrayEquals(media.copyOf(part.length().toInt()), part.readBytes())
        assertTrue(part.length() > 0)
        portalAfterNextCut = false
        cutAt = null
        portal = false
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertArrayEquals(media, mediaFile(engine, viewerA, "1").readBytes())
    }

    @Test
    fun `something unexpected ends one attempt where the viewer can retry it, not the app`() = runBlocking<Unit> {
        val crashes = caughtCrashes()
        signIn(viewerA)
        val engine = started()
        freeThrows = true
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        assertEquals("internal", engine.entry(viewerA, "1")?.job?.failure)
        assertEquals(emptyList<Throwable>(), crashes.toList())
        freeThrows = false
        engine.retry(viewerA.key, entryId("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `an exception in the engine's background work reaches nobody's crash handler`() = runBlocking<Unit> {
        val crashes = caughtCrashes()
        signIn(viewerA)
        val engine = started()
        networkThrows = true
        engine.networkChanged()
        delay(200)
        networkThrows = false
        assertEquals(emptyList<Throwable>(), crashes.toList())
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    private fun caughtCrashes(): MutableList<Throwable> {
        val crashes = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> crashes += error }
        restoreCrashHandler = { Thread.setDefaultUncaughtExceptionHandler(before) }
        return crashes
    }

    @Test
    fun `the engine says work is unfinished until it is ready, paused or failed`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertTrue(unfinishedReports.contains(true))
        assertEquals(false, unfinishedReports.last())
    }

    @Test
    fun `a revocation owed at sign-out goes through when the server returns, with no network change or restart`() = runBlocking<Unit> {
        revocationMs = 100
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        logoutStatus = 500
        accounts.signOut(viewerA.key)
        withTimeout(10_000) {
            while (!engine.files.sessions().readText().contains("pending-revoke")) delay(5)
        }
        logoutStatus = 204
        withTimeout(10_000) {
            while (engine.files.sessions().readText().contains("set-viewer-a")) delay(20)
        }
    }

    @Test
    fun `a real network change ends the rest at once`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        serverOff = true
        restMs = 60_000
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        serverOff = false
        engine.networkChanged()
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `network capability reports that change the answer still count`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        network = OfflineNetwork.none
        engine.networkChanged()
        delay(100)
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        network = OfflineNetwork.unmetered
        engine.networkCapabilitiesChanged()
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `a download waiting for space goes on by itself when space comes back`() = runBlocking<Unit> {
        signIn(viewerA)
        recheckMs = 100
        free = 10
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingStorage)
        delay(100)
        val tried = itemRequests("1")
        delay(400)
        assertEquals("no change in free space, no new attempt", tried, itemRequests("1"))
        free = 10L * 1024 * 1024 * 1024
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    // Fix round: the review's findings, each by what it would have cost the viewer.

    private val converted = OfflinePreset.video720
    private val convertedId get() = offlineEntryId("c", converted)

    /** Ten seconds in fragments of two, against an item that runs ten. */
    private fun conversionOf(fragments: Int): ByteArray = Fmp4Builder.file(fragments)

    private fun convertedItem() = item("c").copy(runtimeTicks = 100_000_000)

    private fun OfflineEngine.convertedEntry() = libraries.value[viewerA.key]?.manifest?.entries?.firstOrNull { it.id == convertedId }

    private suspend fun OfflineEngine.awaitConverted(phase: OfflinePhase) = withTimeout(20_000) {
        libraries.first { all -> all[viewerA.key]?.manifest?.entries?.firstOrNull { it.id == convertedId }?.job?.phase == phase }
        Unit
    }

    private fun conversionRequests() = requests.count { it.path.orEmpty().contains("/Videos/") && it.path.orEmpty().contains("/stream.") }

    private fun downloadRequests() = requests.count { it.path.orEmpty().contains("/Download") }

    @Test
    fun `a conversion that ended early at a fragment boundary never reaches Ready`() = runBlocking<Unit> {
        signIn(viewerA)
        convertedBody = conversionOf(2)
        val engine = started()
        engine.enqueue(viewerA, convertedItem(), OfflineChoice("c", converted))
        engine.awaitConverted(OfflinePhase.failed)
        assertEquals("short", engine.convertedEntry()?.job?.failure)
        assertEquals(2, conversionRequests())
    }

    @Test
    fun `a whole conversion is Ready, and one that came up short the first time gets a second go`() = runBlocking<Unit> {
        signIn(viewerA)
        convertedBody = conversionOf(2)
        afterConversion = conversionOf(5)
        val engine = started()
        engine.enqueue(viewerA, convertedItem(), OfflineChoice("c", converted))
        engine.awaitConverted(OfflinePhase.ready)
        assertEquals(2, conversionRequests())
    }

    @Test
    fun `on a full disk Delete still frees the bytes, nothing escapes, and the record follows when it can`() = runBlocking<Unit> {
        recheckMs = 20
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val file = mediaFile(engine, viewerA, "1")
        assertTrue(file.exists())
        val manifest = engine.files.manifest(viewerA.key)
        // The write can't happen, as it can't on a full disk.
        val temp = File(manifest.parentFile, manifest.name + ".tmp")
        assertTrue(temp.mkdirs())
        engine.delete(viewerA.key, entryId("1"))
        assertFalse(file.exists())
        assertEquals(null, engine.entry(viewerA, "1"))
        assertTrue(manifest.readText().contains(entryId("1")))
        assertTrue(temp.delete())
        withTimeout(10_000) {
            while (manifest.readText().contains(entryId("1"))) delay(10)
        }
    }

    @Test
    fun `no button can take the app down, whatever goes wrong behind it`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        networkThrows = true
        engine.pause(viewerA.key, entryId("1"))
        engine.resume(viewerA.key, entryId("1"))
        engine.retry(viewerA.key, entryId("1"))
        engine.redownload(viewerA.key, entryId("1"))
        engine.setCellular(viewerA.key, entryId("1"), true)
        engine.setWifiOnly(viewerA.key, false)
        engine.setCap(viewerA.key, 1L shl 40)
        engine.refresh()
        engine.enqueue(viewerA, item("2"), choice("2"))
        engine.delete(viewerA.key, entryId("1"))
        networkThrows = false
    }

    @Test
    fun `pause ends a stalled connection at once, and resume doesn't wait out the timeout`() = runBlocking<Unit> {
        signIn(viewerA)
        hold = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.transferring)
        withTimeout(5_000) {
            while (downloadRequests() == 0) delay(5)
        }
        engine.pause(viewerA.key, entryId("1"))
        hold = false
        engine.resume(viewerA.key, entryId("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    /** A read that ignores being told to stop until released, like a call nobody could cancel. */
    private class StubbornHttp(private val media: ByteArray) : OfflineHttp {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val firstBlocked = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val firstEnded = java.util.concurrent.CountDownLatch(1)
        val secondBlocked = java.util.concurrent.CountDownLatch(1)
        val secondCancelled = java.util.concurrent.CountDownLatch(1)

        override fun open(
            url: String,
            headers: Map<String, String>,
            range: String?,
            onCall: (cancel: () -> Unit) -> Unit
        ): OfflineHttpResponse {
            val which = opens.incrementAndGet()
            if (which == 2) onCall { secondCancelled.countDown() }
            val answer = mapOf("Last-Modified" to "Mon", "Accept-Ranges" to "bytes")
            val body = object : java.io.InputStream() {
                override fun read(): Int = throw IOException("unused")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (which == 1) {
                        firstBlocked.countDown()
                        releaseFirst.await()
                        firstEnded.countDown()
                    } else {
                        secondBlocked.countDown()
                        secondCancelled.await()
                    }
                    throw IOException("ended")
                }
            }
            return OfflineHttpResponse(200, { answer[it] }, body, media.size.toLong()) {}
        }
    }

    @Test
    fun `a new attempt never writes beside an old one, and the old one ending can't take Pause away from it`() = runBlocking<Unit> {
        val http = StubbornHttp(media)
        httpOverride = http
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        assertTrue(http.firstBlocked.await(10, java.util.concurrent.TimeUnit.SECONDS))
        engine.pause(viewerA.key, entryId("1"))
        engine.resume(viewerA.key, entryId("1"))
        // Pause and Resume again while it's waiting: a job stopped before it ever ran must not break the chain.
        engine.pause(viewerA.key, entryId("1"))
        engine.resume(viewerA.key, entryId("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.preparing)
        delay(500)
        assertEquals(1, http.opens.get())
        http.releaseFirst.countDown()
        assertTrue(http.firstEnded.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(http.secondBlocked.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(2, http.opens.get())
        // The old attempt's ending had the chance to forget the new one. Pause must still reach it.
        engine.pause(viewerA.key, entryId("1"))
        assertTrue(http.secondCancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `an old attempt losing its connection late doesn't knock back the one that replaced it`() = runBlocking<Unit> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var once = true
        apiClientOverride = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.contains("/Items/") && synchronized(entered) { once.also { once = false } }) {
                entered.countDown()
                release.await()
                throw IOException("late")
            }
            chain.proceed(chain.request())
        }.build()
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        engine.pause(viewerA.key, entryId("1"))
        engine.resume(viewerA.key, entryId("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.preparing)
        val seen = java.util.concurrent.CopyOnWriteArrayList<OfflinePhase>()
        val watcher = launch(Dispatchers.Default) {
            engine.libraries.collect { all ->
                all[viewerA.key]?.manifest?.entries?.firstOrNull()?.job?.phase?.let { seen += it }
            }
        }
        release.countDown()
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        watcher.cancel()
        assertFalse(seen.toString(), OfflinePhase.waitingNetwork in seen)
    }

    @Test
    fun `a drop close to the cap carries on, it doesn't go waiting for storage nobody needs to free`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.setCap(viewerA.key, media.size + 1000L)
        cutAt = 400_000
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertTrue(downloadRequests() >= 2)
    }

    @Test
    fun `after Android's time for the service runs out, the next job run picks the bytes up again`() = runBlocking<Unit> {
        signIn(viewerA)
        hold = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.transferring)
        engine.foregroundTimedOut()
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        hold = false
        delay(200)
        assertEquals(OfflinePhase.waitingNetwork, engine.entry(viewerA, "1")?.job?.phase)
        engine.wake()
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    private class TokenRemote : OfflineProgressRemote {
        val tokens = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun sameViewer(account: Account): Boolean {
            tokens += account.accessToken
            return true
        }

        override suspend fun read(account: Account, itemId: String) = OfflineUserData()
        override suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int? = 200
    }

    @Test
    fun `after a re-sign-in progress syncs with the new token, even from a screen holding the old account`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        val store = engine.progressStore(viewerA)
        store.viewing("1", store.newSequence(), 50)
        accounts.remember(viewerA.copy(accessToken = "token-new"))
        val remote = TokenRemote()
        engine.progressStore(viewerA).sync(remote)
        assertEquals(listOf("token-new"), remote.tokens.toList())
    }

    @Test
    fun `a player still open at sign-out can't put the viewer's history back on disk`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        val store = engine.progressStore(viewerA)
        val sequence = store.newSequence()
        val dir = engine.files.accountDir(viewerA.key)
        accounts.signOut(viewerA.key)
        withTimeout(10_000) { engine.libraries.first { viewerA.key !in it } }
        store.viewing("1", sequence, 50)
        engine.progressStore(viewerA).viewing("1", sequence, 60)
        assertFalse(dir.exists())
        signIn(viewerA)
        withTimeout(10_000) { engine.libraries.first { viewerA.key in it } }
        delay(50)
        val back = engine.progressStore(viewerA)
        back.viewing("2", back.newSequence(), 50)
        assertTrue(engine.files.progress(viewerA.key).exists())
    }

    @Test
    fun `the notification names only the signed-in viewer's downloads`() = runBlocking<Unit> {
        signIn(viewerB, viewerA)
        accounts.select(viewerA.key)
        hold = true
        val engine = started()
        engine.enqueue(viewerB, item("1"), choice("1"))
        engine.awaitPhase(viewerB, "1", OfflinePhase.transferring)
        assertFalse(foreground.toString(), foreground.flatten().contains("Title 1"))
        assertTrue(foreground.flatten().contains(""))
        accounts.select(viewerB.key)
        withTimeout(10_000) {
            while (!foreground.flatten().contains("Title 1")) delay(5)
        }
    }

    @Test
    fun `the notification's words`() {
        val running = OfflineEntry(
            id = "x",
            itemId = "x",
            kind = OfflineMediaKind.movie,
            preset = original,
            labels = OfflineLabels(title = "Mine"),
            job = OfflineJob(OfflinePhase.transferring)
        )
        val theirs = running.copy(labels = OfflineLabels(title = "Theirs"))
        val libraries = mapOf(
            "other" to OfflineLibrary(OfflineManifest(entries = listOf(theirs))),
            "me" to OfflineLibrary(OfflineManifest(entries = listOf(running)))
        )
        assertEquals(listOf("Mine", ""), offlineForegroundTitles(libraries, "me"))
        assertEquals(listOf("", ""), offlineForegroundTitles(libraries, null))
        assertEquals("Mine", offlineNotificationTitle(listOf("Mine", "")))
        assertEquals("Downloads", offlineNotificationTitle(listOf("", "")))
    }

    /** What a kill between the rename and the save leaves. */
    private suspend fun stopAndForgetFinish(engine: OfflineEngine, key: String, entry: String) {
        scopes.forEach { it.cancel() }
        delay(100)
        val file = engine.files.manifest(key)
        val manifest = offlineDecodeManifest(file.readText())!!
        file.writeText(
            offlineEncodeManifest(
                manifest.copy(
                    entries = manifest.entries.map { current ->
                        if (current.id != entry) {
                            current
                        } else {
                            current.copy(
                                job = current.job.copy(phase = OfflinePhase.transferring),
                                resources = current.resources.map { it.copy(complete = false, bytes = null, sha256 = null) }
                            )
                        }
                    }
                )
            )
        )
    }

    @Test
    fun `a finished file the manifest didn't hear about is taken up, not downloaded again`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val before = downloadRequests()
        stopAndForgetFinish(engine, viewerA.key, entryId("1"))
        val second = started()
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertEquals(before, downloadRequests())
        assertArrayEquals(media, mediaFile(second, viewerA, "1").readBytes())
    }

    @Test
    fun `a finished conversion isn't asked of the server twice`() = runBlocking<Unit> {
        signIn(viewerA)
        convertedBody = conversionOf(5)
        val engine = started()
        engine.enqueue(viewerA, convertedItem(), OfflineChoice("c", converted))
        engine.awaitConverted(OfflinePhase.ready)
        val before = conversionRequests()
        stopAndForgetFinish(engine, viewerA.key, convertedId)
        val second = started()
        second.awaitConverted(OfflinePhase.ready)
        assertEquals(before, conversionRequests())
    }

    @Test
    fun `a file left behind that can't be shown whole goes, and doesn't count twice`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val file = mediaFile(engine, viewerA, "1")
        val before = downloadRequests()
        stopAndForgetFinish(engine, viewerA.key, entryId("1"))
        file.writeBytes(media.copyOf(1000))
        val second = started()
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertTrue(downloadRequests() > before)
        assertArrayEquals(media, file.readBytes())
        assertEquals(media.size.toLong(), second.libraries.value[viewerA.key]!!.manifest.budget.usedBytes)
    }

    @Test
    fun `a Wi-Fi sign-in page answering the Quick Connect question doesn't read as this server wanting a password`() = runBlocking<Unit> {
        signIn(viewerA)
        restMs = 200
        portal = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        assertFalse(engine.libraries.value[viewerA.key]!!.needsPassword)
        portal = false
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `after a restart a partial is cut back to what was surely synced, and not during one`() = runBlocking<Unit> {
        signIn(viewerA)
        restartTrust = { 100_000 }
        cutAt = 400_000
        offAfterNextCut = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        val part = File(mediaFile(engine, viewerA, "1").path + OFFLINE_PART_SUFFIX)
        assertTrue(part.length() > 100_000)
        scopes.forEach { it.cancel() }
        delay(100)
        offAfterNextCut = false
        serverOff = false
        cutAt = null
        requests.clear()
        val second = started()
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertEquals("bytes=100000-", requests.first { it.path.orEmpty().contains("/Download") }.getHeader("Range"))
        assertArrayEquals(media, mediaFile(second, viewerA, "1").readBytes())
    }

    @Test
    fun `a dropped connection in a running process keeps all it has`() = runBlocking<Unit> {
        signIn(viewerA)
        restartTrust = { 0 }
        cutAt = 400_000
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val ranges = requests.filter { it.path.orEmpty().contains("/Download") }.mapNotNull { it.getHeader("Range") }
        assertTrue(ranges.toString(), ranges.isNotEmpty() && ranges.none { it == "bytes=0-" })
    }

    @Test
    fun `a heavy trash never holds up the start, and still empties`() = runBlocking<Unit> {
        val release = java.util.concurrent.CountDownLatch(1)
        val emptied = java.util.concurrent.CountDownLatch(1)
        cleaner = {
            release.await()
            emptied.countDown()
        }
        signIn(viewerA)
        val engine = engine()
        withTimeout(5_000) { engine.start().join() }
        release.countDown()
        assertTrue(emptied.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `the ending of a film played from a download is synced, not left for the next network change`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        var exits = 0
        val entry = OfflineEntry("x", "x", OfflineMediaKind.movie, preset = original, labels = OfflineLabels("x"))
        val gateway = OfflinePlaybackGateway(entry, File("x"), { null }, engine.progressStore(viewerA), 1) { exits++ }
        gateway.played()
        assertEquals(1, exits)
    }

    @Test
    fun `pause ends a stalled answer from the server too, so the next attempt isn't held behind it`() = runBlocking<Unit> {
        apiClientOverride = okhttp3.OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build()
        holdItems = true
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        withTimeout(5_000) {
            while (requests.none { it.path.orEmpty().contains("/Items/") }) delay(5)
        }
        engine.pause(viewerA.key, entryId("1"))
        holdItems = false
        engine.resume(viewerA.key, entryId("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }
}
