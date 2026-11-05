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
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.offlineEntryId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
                override fun update(activeTitles: List<String>) {
                    foreground += activeTitles
                }
            },
            http = offlineOkHttp(),
            apiClient = okhttp3.OkHttpClient(),
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
}
