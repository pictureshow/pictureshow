/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.offline.OFFLINE_PART_SUFFIX
import app.pictureshow.core.offline.OfflineCollection
import app.pictureshow.core.offline.OfflineCollectionKind
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineJob
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineManifest
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePhase
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.collectionPlayOrder
import app.pictureshow.core.offline.offlineDecodeManifest
import app.pictureshow.core.offline.offlineEncodeManifest
import app.pictureshow.core.offline.offlineEntryId
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DesktopOfflineEngineTest {
    private val base: File = kotlin.io.path.createTempDirectory("offline-engine").toFile()
    private val server = MockWebServer()
    private val media = ByteArray(900_000) { (it % 241).toByte() }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val deviceFor = ConcurrentHashMap<String, String>()
    private val scopes = mutableListOf<CoroutineScope>()
    @Volatile private var free: Long? = 10L * 1024 * 1024 * 1024
    @Volatile private var network = OfflineNetwork.unmetered
    @Volatile private var cutAt: Int? = null
    @Volatile private var hold = false
    @Volatile private var slow = false
    @Volatile private var quickConnect = true
    @Volatile private var logoutStatus = 204
    private val logoutResponses = Channel<Int>(Channel.UNLIMITED)
    private val revocationWriteRefused = CompletableDeferred<String>()

    @Volatile private var freshItem: String? = null

    @Volatile private var downloadsAllowed = true

    @Volatile
    private var serverOff = false
    @Volatile private var restMs = 1L
    private var recheckMs = 30_000L
    private var revocationMs = 60_000L

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
    private var httpOverride: DesktopOfflineHttp? = null
    private val identity = ClientIdentity("Test", "install-id", "1")
    private var apiOverride: DesktopOfflineApi? = null
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
        File(base, "offline").deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (serverOff) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                if (portal) return MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Sign in to Wi-Fi</html>")
                val path = request.path.orEmpty().substringBefore('?')
                return when {
                    holdItems && path.contains("/Items/") -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    path.startsWith("/Users/") && path.contains("/Items/") -> json(freshItem ?: itemJson(path.substringAfterLast('/')))
                    path.startsWith("/UserItems/") -> userItem(request)
                    path == "/Users/Me" -> json(
                        """{"Id":"${meId ?: ""}","Policy":{"EnableContentDownloading":$downloadsAllowed,"EnableMediaPlayback":true,
                        "EnableVideoPlaybackTranscoding":true,"EnableAudioPlaybackTranscoding":true}}"""
                    )
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
                    path == "/Sessions/Logout" -> {
                        val status = logoutStatus
                        logoutResponses.trySend(status)
                        MockResponse().setResponseCode(status)
                    }
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

    @After
    fun tearDown() {
        restoreCrashHandler()
        hold = false
        try {
            runBlocking { stopScopes() }
            base.deleteRecursively()
        } finally {
            server.shutdown()
        }
    }

    // A cancelled job can still be closing a file or persisting its final state.
    private suspend fun stopScopes() {
        scopes.forEach { it.cancel() }
        withTimeout(40_000) { scopes.forEach { it.coroutineContext[Job]?.join() } }
        scopes.clear()
    }

    /** The server's own place for every item, as a real server keeps it: written by POST, read back by GET. */
    @Volatile private var meId: String? = null
    private val serverPlace = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    private fun userItem(request: RecordedRequest): MockResponse {
        val id = request.path.orEmpty().removePrefix("/UserItems/").substringBefore('/')
        if (request.method == "POST") {
            val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()) as kotlinx.serialization.json.JsonObject
            val (ticks, played) = serverPlace[id] ?: (0L to false)
            serverPlace[id] = (body["PlaybackPositionTicks"]?.toString()?.toLong() ?: ticks) to (body["Played"]?.toString()?.toBoolean() ?: played)
            return json("{}")
        }
        val (ticks, played) = serverPlace[id] ?: (0L to false)
        return json("""{"PlaybackPositionTicks":$ticks,"Played":$played,"PlayCount":0}""")
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

    private fun engine(): DesktopOfflineEngine {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopes += scope
        return DesktopOfflineEngine(
            base,
            accounts,
            identity,
            log = { if (it.startsWith("offline revocation-not-saved ")) revocationWriteRefused.complete(it) },
            http = httpOverride,
            api = apiOverride,
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

    private fun item(id: String, size: Long = media.size.toLong()) = DesktopOfflineItem(
        id,
        "Title $id",
        "Movie",
        userData = DesktopOfflineUserData(),
        sources = listOf(
            DesktopOfflineSource("s$id", "etag", "mkv", size, streams = listOf(DesktopOfflineStream(0, "Video"), DesktopOfflineStream(1, "Audio")))
        )
    )

    private val original = OfflinePreset.original
    private fun choice(id: String, cellular: Boolean = false) = DesktopOfflineChoice(id, original, cellular = cellular)
    private fun entryId(id: String) = offlineEntryId(id, original)

    private suspend fun DesktopOfflineEngine.awaitPhase(account: Account, id: String, phase: OfflinePhase) {
        val reached = withTimeoutOrNull(20_000) {
            libraries.first { all -> all[account.key]?.manifest?.entries?.firstOrNull { it.id == entryId(id) }?.job?.phase == phase }
        }
        assertTrue("Expected $id in $phase, last job: ${entry(account, id)?.job}", reached != null)
    }

    private suspend fun signIn(vararg all: Account) = all.forEach { accounts.remember(it) }

    private suspend fun started(): DesktopOfflineEngine = engine().also { it.start().join() }

    private fun DesktopOfflineEngine.entry(account: Account, id: String) = libraries.value[account.key]?.manifest?.entries?.firstOrNull {
        it.id ==
            entryId(id)
    }

    private fun mediaFile(engine: DesktopOfflineEngine, account: Account, id: String): File {
        val entry = engine.entry(account, id)!!
        return engine.resourceFile(account.key, entry, entry.resources.first { it.id == "media" })
    }

    @Test
    fun `a stale completed preview cannot download a recording that is now growing`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        freshItem = itemJson("1").replace("\"Type\":\"Movie\"", "\"Type\":\"Episode\",\"IsInProgress\":true")
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        assertEquals("recording-incomplete", engine.entry(viewerA, "1")?.job?.failure)
        assertFalse(requests.any { it.path.orEmpty().substringBefore('?').endsWith("/Download") })
        assertFalse(mediaFile(engine, viewerA, "1").exists())
    }

    @Test
    fun `download permission is rechecked after the preview and before bytes move`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        downloadsAllowed = false
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        assertEquals("denied", engine.entry(viewerA, "1")?.job?.failure)
        assertFalse(requests.any { it.path.orEmpty().substringBefore('?').endsWith("/Download") })
    }

    @Test
    fun `item download denial on fresh read blocks an earlier permitted recording`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        freshItem = itemJson("1").replace("\"Type\":\"Movie\"", "\"Type\":\"Episode\",\"CanDownload\":false")
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.failed)
        assertEquals("denied", engine.entry(viewerA, "1")?.job?.failure)
        assertFalse(requests.any { it.path.orEmpty().substringBefore('?').endsWith("/Download") })
    }

    @Test
    fun `completed finite recording downloads through original path with verified local bytes`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        freshItem = itemJson("1").replace(
            "\"Type\":\"Movie\"",
            "\"Type\":\"Episode\",\"CanDownload\":true,\"Status\":\"Completed\",\"IsInProgress\":false"
        )
        val recording = kotlinx.serialization.json.Json.decodeFromString<DesktopOfflineItem>(freshItem!!)
        engine.enqueue(viewerA, recording, choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertArrayEquals(media, mediaFile(engine, viewerA, "1").readBytes())
        assertTrue(requests.any { it.path == "/Items/1/Download" })
        assertEquals(app.pictureshow.core.offline.OfflineMediaKind.episode, engine.entry(viewerA, "1")?.kind)
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
        offAfterNextCut = true
        restMs = 60_000
        val first = started()
        first.enqueue(viewerA, item("1"), choice("1"))
        first.awaitPhase(viewerA, "1", OfflinePhase.waitingNetwork)
        stopScopes()
        val partial = File(mediaFile(first, viewerA, "1").path + OFFLINE_PART_SUFFIX).length()
        assertTrue("Expected an incomplete file from the old process", partial in 1 until media.size.toLong())
        requests.clear()
        offAfterNextCut = false
        cutAt = null
        serverOff = false
        val second = started()
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertArrayEquals(media, mediaFile(second, viewerA, "1").readBytes())
        assertEquals("bytes=$partial-", requests.single { it.path.orEmpty().contains("/Download") }.getHeader("Range"))
    }

    @Test
    fun `delete removes partials and a late success can't bring it back`() = runBlocking<Unit> {
        signIn(viewerA)
        hold = true
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.transferring)
        engine.delete(viewerA.key, entryId("1"))
        hold = false
        stopScopes()
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
        engine.awaitPhase(viewerA, "1", OfflinePhase.waitingStorage)
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
        accounts.signOutKeepingAccount(viewerA.key)
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
        accounts.signOutKeepingAccount(viewerA.key)
        withTimeout(10_000) { engine.libraries.first { viewerA.key !in it } }
        assertFalse(dirA.exists())
        awaitLogoutResponse(500)
        val pending = withTimeout(10_000) { engine.sessions.snapshot() }
        assertEquals("pending-revoke", pending.state.records.single().phase)
        assertEquals(listOf("set-viewer-a"), pending.tokens.values.toList())
        logoutStatus = 204
        engine.networkChanged()
        awaitLogoutResponse(204)
        assertRevocationFinished(engine)
    }

    @Test
    fun `one viewer's slow logout does not hold up another viewer's local purge`() = runBlocking<Unit> {
        val firstLogout = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        apiOverride = object : DesktopOfflineApi(identity) {
            override suspend fun logout(serverUrl: String, deviceId: String, token: String) {
                if (token == "set-viewer-a") {
                    firstLogout.complete(Unit)
                    releaseLogout.await()
                }
                super.logout(serverUrl, deviceId, token)
            }
        }
        signIn(viewerA, viewerB)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        engine.enqueue(viewerB, item("2"), choice("2"))
        engine.awaitPhase(viewerB, "2", OfflinePhase.ready)
        try {
            accounts.signOutKeepingAccount(viewerA.key)
            withTimeout(10_000) { firstLogout.await() }
            accounts.signOutKeepingAccount(viewerB.key)
            withTimeout(10_000) { engine.libraries.first { viewerB.key !in it } }
            assertFalse(engine.files.accountDir(viewerB.key).exists())
        } finally {
            releaseLogout.complete(Unit)
        }
    }

    @Test
    fun `a Windows reader holding the session file cannot lose a sign-out revocation`() = runBlocking<Unit> {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        revocationMs = 100
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        // Hold open the same kind of file handle the old test's readText poll used.
        FileInputStream(engine.files.sessions()).use {
            accounts.signOutKeepingAccount(viewerA.key)
            withTimeout(10_000) { revocationWriteRefused.await() }
            assertFalse(engine.files.accountDir(viewerA.key).exists())
        }
        awaitLogoutResponse(204)
        assertRevocationFinished(engine)
    }

    @Test
    fun `removing a viewer's card also takes their downloads`() = runBlocking<Unit> {
        signIn(viewerA, viewerB)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val dirA = engine.files.accountDir(viewerA.key)
        accounts.removeAccount(viewerA.key)
        withTimeout(10_000) { engine.libraries.first { viewerA.key !in it } }
        assertFalse(dirA.exists())
    }

    @Test
    fun `a session the server expired keeps its downloads for the next sign-in`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val bytes = mediaFile(engine, viewerA, "1")
        accounts.expire(viewerA.key, viewerA.accessToken)
        delay(200)
        assertTrue(viewerA.key in engine.libraries.value)
        assertTrue(bytes.exists())
    }

    @Test
    fun `a signed-out card kept on the device owns no downloads at next start`() = runBlocking<Unit> {
        signIn(viewerA, viewerB)
        val dir = OfflineFiles(base).accountDir(viewerA.key)
        dir.mkdirs()
        File(dir, "manifest.json").writeText("{}")
        accounts.signOutKeepingAccount(viewerA.key)
        val engine = started()
        assertFalse(dir.exists())
        assertFalse(viewerA.key in engine.libraries.value)
        assertTrue(viewerB.key in engine.libraries.value)
    }

    @Test
    fun `downloads of an account no longer on the device are gone at next start`() = runBlocking<Unit> {
        signIn(viewerB)
        val orphan = OfflineFiles(base).accountDir(viewerA.key)
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
        stopScopes()
        val second = started()
        assertEquals(OfflinePhase.missing, second.entry(viewerA, "1")!!.job.phase)
        second.redownload(viewerA.key, entryId("1"))
        second.awaitPhase(viewerA, "1", OfflinePhase.ready)
    }

    @Test
    fun `a manifest this version can't read is kept and shown recoverable`() = runBlocking<Unit> {
        signIn(viewerA)
        val manifest = OfflineFiles(base).manifest(viewerA.key)
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
        val failed = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            engine.libraries.first { it[viewerA.key]?.manifest?.entries?.singleOrNull()?.job?.phase == OfflinePhase.failed }
                .getValue(viewerA.key)
        }
        engine.enqueue(viewerA, item("1"), choice("1"))
        val library = withTimeout(20_000) { failed.await() }
        assertEquals("needs-password", library.manifest.entries.single().job.failure)
        assertTrue("The first failed state must include its password prompt", library.needsPassword)
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

    private var restoreCrashHandler: () -> Unit = {}

    private fun caughtCrashes(): MutableList<Throwable> {
        val crashes = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> crashes += error }
        restoreCrashHandler = { Thread.setDefaultUncaughtExceptionHandler(before) }
        return crashes
    }

    private suspend fun awaitLogoutResponse(status: Int) = withTimeout(10_000) {
        while (logoutResponses.receive() != status) Unit
    }

    private suspend fun assertRevocationFinished(engine: DesktopOfflineEngine) {
        val saved = withTimeout(10_000) { engine.sessions.snapshot() }
        assertTrue(saved.state.records.isEmpty())
        assertTrue(saved.tokens.isEmpty())
    }

    @Test
    fun `a revocation owed at sign-out goes through when the server returns, with no network change or restart`() = runBlocking<Unit> {
        revocationMs = 100
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        logoutStatus = 500
        accounts.signOutKeepingAccount(viewerA.key)
        awaitLogoutResponse(500)
        logoutStatus = 204
        awaitLogoutResponse(204)
        assertRevocationFinished(engine)
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

    private fun DesktopOfflineEngine.convertedEntry() = libraries.value[viewerA.key]?.manifest?.entries?.firstOrNull { it.id == convertedId }

    private suspend fun DesktopOfflineEngine.awaitConverted(phase: OfflinePhase) = withTimeout(20_000) {
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
        engine.enqueue(viewerA, convertedItem(), DesktopOfflineChoice("c", converted))
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
        engine.enqueue(viewerA, convertedItem(), DesktopOfflineChoice("c", converted))
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
        // The save can't happen, as it can't on a full disk. Desktop's atomic write uses a fresh temporary name each time, so the
        // save is refused at its rename instead of at a fixed temporary path.
        val saved = manifest.readText()
        assertTrue(manifest.delete())
        val inTheWay = File(manifest, "in-the-way")
        assertTrue(inTheWay.mkdirs())
        engine.delete(viewerA.key, entryId("1"))
        assertFalse(file.exists())
        assertEquals(null, engine.entry(viewerA, "1"))
        assertTrue(saved.contains(entryId("1")))
        assertFalse("nothing escapes beside the manifest", manifest.parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertTrue(inTheWay.delete())
        assertTrue(manifest.delete())
        withTimeout(10_000) {
            while (!manifest.isFile || manifest.readText().contains(entryId("1"))) delay(10)
        }
    }

    @Test
    fun `a manifest that can't take its new name is kept in memory and saved when it can`() = runBlocking<Unit> {
        recheckMs = 20
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val manifest = engine.files.manifest(viewerA.key)
        // The temp file writes and the rename refuses, which is the other way a save can fail.
        assertTrue(manifest.delete())
        val inTheWay = File(manifest, "in-the-way")
        assertTrue(inTheWay.mkdirs())
        engine.delete(viewerA.key, entryId("1"))
        assertEquals(null, engine.entry(viewerA, "1"))
        assertTrue(inTheWay.delete())
        assertTrue(manifest.delete())
        withTimeout(10_000) {
            while (!manifest.isFile) delay(10)
        }
        assertFalse(manifest.readText().contains(entryId("1")))
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
    private class StubbornHttp(private val media: ByteArray) : DesktopOfflineHttp {
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
        ): DesktopOfflineHttpResponse {
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
            return DesktopOfflineHttpResponse(200, { answer[it] }, body, media.size.toLong())
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
    fun `a drop close to the cap carries on, it doesn't go waiting for storage nobody needs to free`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.setCap(viewerA.key, media.size + 1000L)
        cutAt = 400_000
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertTrue(downloadRequests() >= 2)
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

    private class ServerAt(var data: OfflineUserData) : OfflineProgressRemote {
        val writes = mutableListOf<Long?>()
        override suspend fun sameViewer(account: Account) = true
        override suspend fun read(account: Account, itemId: String) = data
        override suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int? {
            writes += positionTicks
            positionTicks?.let { data = data.copy(positionTicks = it) }
            return 200
        }
    }

    @Test
    fun `online viewing past an undismissed conflict is what the next local viewing starts from`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        val store = engine.progressStore(viewerA)
        // The engine syncs this outbox to the server on its own; only this test's sync may write here.
        serverOff = true
        val server = ServerAt(OfflineUserData(positionTicks = 900))
        store.remember("1", OfflineUserData(positionTicks = 100))
        store.viewing("1", store.newSequence(), 500)
        store.sync(server)
        assertTrue("1" in store.conflicts.value)
        server.data = OfflineUserData(positionTicks = 30_000)
        engine.onlineViewingEnded(viewerA, "1") { server.data }.join()
        assertTrue("the online viewing replaces what the notice was about", store.conflicts.value.isEmpty())
        assertEquals(30_000, store.resumeTicks("1"))
        store.viewing("1", store.newSequence(), 30_500)
        store.sync(server)
        assertEquals("never the older place", listOf<Long?>(30_500L), server.writes)
    }

    @Test
    fun `a record that sync hasn't sent, then online viewing, isn't this device's own conflict`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        val store = engine.progressStore(viewerA)
        // The engine syncs this outbox to the server on its own; only this test's sync may write here.
        serverOff = true
        val server = ServerAt(OfflineUserData(positionTicks = 30_000))
        store.remember("1", OfflineUserData(positionTicks = 100))
        store.viewing("1", store.newSequence(), 500)
        engine.onlineViewingEnded(viewerA, "1") { server.data }.join()
        assertEquals(30_000, store.resumeTicks("1"))
        store.sync(server)
        assertTrue(store.conflicts.value.isEmpty())
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun `online viewing that didn't move the server leaves the unsent place alone`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        val store = engine.progressStore(viewerA)
        // The engine syncs this outbox to the server on its own; only this test's sync may write here.
        serverOff = true
        val server = ServerAt(OfflineUserData(positionTicks = 100))
        store.remember("1", server.data)
        store.viewing("1", store.newSequence(), 500)
        engine.onlineViewingEnded(viewerA, "1") { server.data }.join()
        assertEquals(500, store.resumeTicks("1"))
        store.sync(server)
        assertEquals(listOf<Long?>(500L), server.writes)
    }

    @Test
    fun `a conflict's next local viewing starts at the place the notice says was kept`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        val store = engine.progressStore(viewerA)
        // The engine syncs this outbox to the server on its own; only this test's sync may write here.
        serverOff = true
        val server = ServerAt(OfflineUserData(positionTicks = 900))
        store.remember("1", OfflineUserData(positionTicks = 100))
        store.viewing("1", store.newSequence(), 500)
        store.sync(server)
        assertEquals(900, store.resumeTicks("1"))
    }

    @Test
    fun `online viewing of a downloaded title moves where the local viewing starts, and other titles read nothing`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        var reads = 0
        engine.onlineViewingEnded(viewerA, "1") {
            reads++
            OfflineUserData(positionTicks = 7_000)
        }.join()
        assertEquals(7_000, engine.progressStore(viewerA).resumeTicks("1"))
        engine.onlineViewingEnded(viewerA, "not-downloaded") {
            reads++
            OfflineUserData(positionTicks = 9_000)
        }.join()
        assertEquals(1, reads)
        assertEquals(0, engine.progressStore(viewerA).resumeTicks("not-downloaded"))
        engine.onlineViewingEnded(viewerA, "1") { throw IOException("server gone") }.join()
        assertEquals(7_000, engine.progressStore(viewerA).resumeTicks("1"))
    }

    @Test
    fun `reads after stopping and after finishing land in order, however long the first takes`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        val slow = CompletableDeferred<Unit>()
        val stopped = engine.onlineViewingEnded(viewerA, "1") {
            slow.await()
            OfflineUserData(positionTicks = 9_000)
        }
        val finished = engine.onlineViewingEnded(viewerA, "1") { OfflineUserData(positionTicks = 0, played = true) }
        withTimeoutOrNull(200) { finished.join() }
        slow.complete(Unit)
        stopped.join()
        finished.join()
        assertEquals(0, engine.progressStore(viewerA).resumeTicks("1"))
        assertEquals(setOf("1"), engine.progressStore(viewerA).watched(listOf("1")))
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
        accounts.signOutKeepingAccount(viewerA.key)
        withTimeout(10_000) { engine.libraries.first { viewerA.key !in it } }
        store.viewing("1", sequence, 50)
        engine.progressStore(viewerA).viewing("1", sequence, 60)
        assertFalse(dir.exists())
        signIn(viewerA)
        withTimeout(10_000) { engine.libraries.first { viewerA.key in it } }
        val back = engine.progressStore(viewerA)
        back.viewing("2", back.newSequence(), 50)
        assertTrue(engine.files.progress(viewerA.key).exists())
    }

    /** What a kill between the rename and the save leaves. */
    private suspend fun stopAndForgetFinish(engine: DesktopOfflineEngine, key: String, entry: String) {
        stopScopes()
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
        engine.enqueue(viewerA, convertedItem(), DesktopOfflineChoice("c", converted))
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
        stopScopes()
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

    private fun desktopOffline(engine: DesktopOfflineEngine, account: Account = viewerA) = DesktopOffline(
        engine,
        DesktopOfflineApi(ClientIdentity("Test", "install-id", "1")),
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it },
        kotlinx.coroutines.flow.flowOf(account),
        { account }
    )

    @Test
    fun `the ending of a film played from a download is synced to the server at once`() = runBlocking<Unit> {
        signIn(viewerA)
        meId = "viewer-a"
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val owner = desktopOffline(engine)
        val local = owner.prepareLocal(viewerA, engine.entry(viewerA, "1")!!, fromStart = true)!!
        owner.localProgress(local, 90.0)
        owner.localCompleted(local)
        owner.localEnded(local)
        withTimeout(10_000) { while (serverPlace["1"]?.second != true) delay(10) }
        withTimeout(10_000) { while (engine.progressStore(viewerA).place("1").first?.phase.let { it != null && it != "synced" }) delay(10) }
        assertEquals(null, owner.state.value.notice)
    }

    @Test
    fun `a ready download plays as a local file with no server session, from the unsent place`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val store = engine.progressStore(viewerA)
        store.viewing("1", store.newSequence(), 420_000_000)
        val owner = desktopOffline(engine)
        val local = owner.prepareLocal(viewerA, engine.entry(viewerA, "1")!!, fromStart = false,
            server = app.pictureshow.core.ItemUserData(playbackPositionTicks = 10))!!
        assertEquals(mediaFile(engine, viewerA, "1").canonicalFile, local.decision.localFile()!!.canonicalFile)
        assertTrue(local.decision.headers.isEmpty())
        assertEquals(null, local.decision.playSessionId)
        assertEquals(app.pictureshow.core.playback.PlayMethod.DirectPlay, local.decision.method)
        assertEquals(app.pictureshow.core.playback.PlaybackEngine.Mpv, local.decision.engine)
        // The unsent local place is newer than anything the server holds.
        assertEquals(42.0, local.startSeconds, 0.001)
        assertEquals(0.0, owner.prepareLocal(viewerA, engine.entry(viewerA, "1")!!, fromStart = true)!!.startSeconds, 0.0)
    }

    @Test
    fun `a download whose file went is not opened, and becomes missing`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        assertTrue(mediaFile(engine, viewerA, "1").delete())
        assertEquals(null, desktopOffline(engine).prepareLocal(viewerA, engine.entry(viewerA, "1")!!, fromStart = false))
        engine.awaitPhase(viewerA, "1", OfflinePhase.missing)
    }

    @Test
    fun `local progress is on disk before anything is sent, and survives a restart`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        serverOff = true
        val owner = desktopOffline(engine)
        val local = owner.prepareLocal(viewerA, engine.entry(viewerA, "1")!!, fromStart = true)!!
        owner.localProgress(local, 12.5)
        assertEquals(125_000_000L, engine.progressStore(viewerA).place("1").first!!.positionTicks)
        owner.localCompleted(local)
        stopScopes()
        val again = started()
        val (outbox, _) = again.progressStore(viewerA).place("1")
        assertEquals(true, outbox!!.played)
        assertNotEquals("synced", outbox.phase)
        assertFalse(requests.any { it.path.orEmpty().startsWith("/UserItems/") && it.method == "POST" })
    }

    @Test
    fun `the controller shows only the current viewer's downloads and knows when one is ready`() = runBlocking<Unit> {
        signIn(viewerA, viewerB)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        val forB = desktopOffline(engine, viewerB).also { it.start() }
        val forA = desktopOffline(engine, viewerA).also { it.start() }
        withTimeout(10_000) { while (forA.state.value.readyFor("1") == null) delay(10) }
        assertTrue(forA.readyFor(viewerA))
        assertFalse(forB.readyFor(viewerB))
        assertTrue(forB.state.value.library.manifest.entries.isEmpty())
        assertEquals(app.pictureshow.core.offline.OfflineDetailSource.local, forA.detailSource(viewerA, "1"))
        assertEquals(app.pictureshow.core.offline.OfflineDetailSource.stream, forA.detailSource(viewerA, "1", explicitStream = true))
        forA.serverReach(true)
        assertEquals(app.pictureshow.core.offline.OfflineDetailSource.unavailable, forA.detailSource(viewerA, "2"))
    }

    @Test
    fun `a launch that can't reach the server lands on Downloads only when something saved can play`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        engine.enqueue(viewerA, item("1"), choice("1"))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        stopScopes()
        serverOff = true
        DesktopController(base, store = accounts, playbackFactory = { error("no player in this test") }).use { c ->
            c.start()
            withTimeout(20_000) { c.state.first { it.route == DesktopRoute.Downloads } }
            assertEquals(DESKTOP_ROOT_DOWNLOADS, c.state.value.root)
            assertEquals(viewerA.key, c.state.value.account?.key)
            assertTrue(c.offline.state.value.serverUnreachable)
            withTimeout(10_000) { while (c.offline.state.value.readyFor("1") == null) delay(10) }
        }
    }

    @Test
    fun `a launch that can't reach the server with nothing saved stays on Home with the error`() = runBlocking<Unit> {
        signIn(viewerA)
        serverOff = true
        DesktopController(base, store = accounts, playbackFactory = { error("no player in this test") }).use { c ->
            c.start()
            val landed = withTimeout(20_000) { c.state.first { it.route != DesktopRoute.Loading && !it.busy } }
            assertEquals(DesktopRoute.Home, landed.route)
            assertTrue(landed.error != null)
        }
    }

    /** MUS-10: an album is its tracks as ordinary downloads plus its order; removing it keeps what another download still holds. */
    @Test
    fun `an album downloads each track once keeps its order and removing it spares a shared track`() = runBlocking<Unit> {
        signIn(viewerA)
        val engine = started()
        val album = OfflineCollection("album", OfflineCollectionKind.album, "Album", itemIds = listOf("1", "2", "1"))
        assertTrue(engine.enqueueCollection(viewerA, album, listOf(item("1"), item("2"), item("1")), cellular = false))
        engine.awaitPhase(viewerA, "1", OfflinePhase.ready)
        engine.awaitPhase(viewerA, "2", OfflinePhase.ready)
        val manifest = engine.libraries.value[viewerA.key]!!.manifest
        assertEquals(listOf(entryId("1"), entryId("2")), manifest.entries.map { it.id })
        assertEquals(listOf("1", "2", "1"), manifest.collectionPlayOrder("album"))
        // A playlist that shares track 2 keeps it when the album goes.
        val playlist = OfflineCollection("list", OfflineCollectionKind.playlist, "List", itemIds = listOf("2"))
        assertTrue(engine.enqueueCollection(viewerA, playlist, listOf(item("2")), cellular = false))
        assertEquals(2, engine.libraries.value[viewerA.key]!!.manifest.entries.size)
        engine.deleteCollection(viewerA.key, "album")
        withTimeout(20_000) {
            engine.libraries.first { all -> all[viewerA.key]?.manifest?.entries?.none { it.itemId == "1" } == true }
        }
        val after = engine.libraries.value[viewerA.key]!!.manifest
        assertEquals(listOf("list"), after.collections.map { it.id })
        assertEquals(OfflinePhase.ready, engine.entry(viewerA, "2")?.job?.phase)
        assertTrue(mediaFile(engine, viewerA, "2").exists())
        // The last track gone takes its snapshot with it.
        engine.delete(viewerA.key, entryId("2"))
        withTimeout(20_000) { engine.libraries.first { all -> all[viewerA.key]?.manifest?.collections?.isEmpty() == true } }
    }

    private fun song(id: String) = DesktopOfflineItem(
        id, "Song $id", "Audio", userData = DesktopOfflineUserData(),
        sources = listOf(DesktopOfflineSource("s$id", "etag", "flac", media.size.toLong(), streams = listOf(DesktopOfflineStream(0, "Audio"))))
    )

    /** MUS-10: the music player's hook reads the same downloads; files play with no server, in the saved order, for their viewer only. */
    @Test
    fun `a downloaded album plays from its files through the music hook in its saved order, for its viewer only`() = runBlocking<Unit> {
        signIn(viewerA, viewerB)
        val engine = started()
        val album = OfflineCollection("album", OfflineCollectionKind.album, "Album", itemIds = listOf("a1", "a2"))
        assertTrue(engine.enqueueCollection(viewerA, album, listOf(song("a1"), song("a2")), cellular = false))
        engine.awaitPhase(viewerA, "a1", OfflinePhase.ready)
        engine.awaitPhase(viewerA, "a2", OfflinePhase.ready)
        val local = DesktopEngineMusicLocal(engine)
        assertEquals(setOf("a1", "a2"), local.downloaded(viewerA))
        assertEquals(emptySet<String>(), local.downloaded(viewerB))
        assertEquals(null, local.decision(viewerB, "a1"))
        val decision = local.decision(viewerA, "a1")!!
        assertTrue(decision.url, decision.url.startsWith("file:"))
        assertTrue(decision.headers.isEmpty())
        assertEquals(null, decision.playSessionId)
        assertArrayEquals(media, decision.localFile()!!.readBytes())
        // Downloads' Play on the second track plays the album from there.
        val library = engine.libraries.value[viewerA.key]
        val play = desktopOfflineMusicPlay(viewerA, library, engine.entry(viewerA, "a2")!!, "album")!!
        assertEquals(listOf("a1", "a2"), play.tracks.map { it.id })
        assertEquals(1, play.start)
        assertEquals("MusicAlbum:album", play.context)
        assertEquals(viewerA.key, play.owner)
        // A file that went is not offered, so the player streams it instead of failing on a missing file.
        mediaFile(engine, viewerA, "a1").delete()
        assertEquals(null, local.decision(viewerA, "a1"))
    }

    /** MUS-10: a downloaded track's listening is kept on disk for later, finished or not, and never sent live. */
    @Test
    fun `listening to a downloaded track goes to the outbox, played when it reached the end`() = runBlocking<Unit> {
        signIn(viewerA)
        serverOff = true
        val engine = started()
        val local = DesktopEngineMusicLocal(engine)
        val runtime = 2_000_000_000L
        local.report(viewerA, "t1", 1, "start", 0, runtime)
        local.report(viewerA, "t1", 1, "progress", 300_000_000, runtime)
        assertEquals(300_000_000L, engine.progressStore(viewerA).place("t1").first!!.positionTicks)
        local.report(viewerA, "t1", 1, "stopped", runtime, runtime)
        assertEquals(true, engine.progressStore(viewerA).place("t1").first!!.played)
        local.report(viewerA, "t2", 2, "stopped", 500_000_000, runtime)
        val cut = engine.progressStore(viewerA).place("t2").first!!
        assertNotEquals(true, cut.played)
        assertEquals(500_000_000L, cut.positionTicks)
        assertFalse(requests.any { it.path.orEmpty().startsWith("/Sessions/Playing") })
    }
}
