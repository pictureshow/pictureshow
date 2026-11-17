/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.seerr.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*

/** Stream C2 (AUTH-15/16/18/19, SEERR-01..07, INFO-01..08) without native code or Compose. */
class DesktopAccountsStreamTest {
    private val server = Server("srv", "Home", "http://127.0.0.1:1", "10.11.0")
    private val ana = Account(server, User("u-ana", "Ana"), "token-ana")
    private val ben = Account(server, User("u-ben", "Ben"), "token-ben")
    private fun dir(): File = Files.createTempDirectory("desktop-172-c2-accounts").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun stop() { scope.cancel() }

    private fun await(what: String, timeoutMs: Long = 10_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) { if (System.currentTimeMillis() > end) fail("timed out: $what"); Thread.sleep(10) }
    }

    private class Clock(var now: Long = 1_000_000L)

    /** Core saves memory first, then disk: a restart reads the file once it holds what [ready] expects. */
    private fun restart(d: File, clock: Clock, logs: MutableList<String> = mutableListOf(), ready: (ProfileLock?) -> Boolean): DesktopProfileLock {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            val lock = DesktopProfileLock(d, scope, { clock.now }, log = { logs += it })
            runBlocking { lock.load() }
            if (ready(lock.lock(ana.key))) return lock
            if (System.currentTimeMillis() > end) fail("the PIN file never caught up")
            Thread.sleep(20)
        }
    }

    /** Sets [pin] for [account] through the Settings flow (first PIN needs no current PIN). */
    private fun setPin(lock: DesktopProfileLock, account: Account, pin: String) {
        lock.viewerChanged(account)
        await("unlocked") { lock.state.value.unlocked == account.key }
        lock.beginSetOrChange(account)
        assertNotNull(lock.state.value.setup)
        lock.setupEnter(pin); lock.setupEnter(pin)
        await("saved") { lock.state.value.locks[account.key] != null }
    }

    @Test fun pinIsHashedOnDiskAndNeverLogged() = runBlocking {
        val d = dir(); val logs = CopyOnWriteArrayList<String>(); val clock = Clock()
        val lock = DesktopProfileLock(d, scope, { clock.now }, log = { logs += it })
        lock.load()
        setPin(lock, ana, "48151623")
        // A fresh start asks (Ask when Pictureshow opens is on by default); a wrong try is refused and clears the pad.
        val restarted = restart(d, clock, logs) { it != null }
        val disk = File(d, "profile-locks.json").readText()
        assertFalse("48151623" in disk, "the PIN itself is never stored")
        restarted.viewerChanged(ana)
        assertEquals(DesktopPinPurpose.GATE, restarted.state.value.gate?.purpose)
        assertTrue(restarted.gated())
        restarted.submit("0000")
        await("wrong") { restarted.state.value.gate?.note == DesktopPinNote.WRONG }
        assertEquals(1, restarted.state.value.gate?.tries)
        restarted.submit("48151623")
        await("unlocked") { restarted.state.value.gate == null }
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "48151623" in it || "0000" in it || "Ana" in it }, "logs carry result kinds only: $logs")
    }

    @Test fun askWhenOpensOffMeansOnlySwitchingAsks() = runBlocking {
        val d = dir(); val clock = Clock()
        val lock = DesktopProfileLock(d, scope, { clock.now })
        lock.load(); setPin(lock, ana, "2468")
        lock.setAskOnStart(ana, false)
        assertEquals(DesktopPinPurpose.CONFIRM_ASK_OFF, lock.state.value.prompt?.purpose, "turning off the start question asks for the PIN")
        lock.submit("2468")
        await("ask off") { lock.state.value.locks[ana.key]?.askOnStart == false }
        val restarted = restart(d, clock) { it?.askOnStart == false }
        restarted.viewerChanged(ana)
        assertNull(restarted.state.value.gate, "Ask when Pictureshow opens is off")
        // Switching from Ben back to Ana asks for Ana's PIN; nothing switches until it is right.
        restarted.viewerChanged(ben)
        assertTrue(restarted.neededOnSwitch(ana.key, ben.key))
        var switched = false
        restarted.askBeforeSwitch(ana) { switched = true }
        assertEquals(DesktopPinPurpose.SWITCH, restarted.state.value.prompt?.purpose)
        restarted.submit("1357")
        await("wrong") { restarted.state.value.prompt?.note == DesktopPinNote.WRONG }
        assertFalse(switched)
        restarted.submit("2468")
        await("switched") { switched }
        assertNull(restarted.state.value.prompt)
    }

    @Test fun changingOrRemovingAsksForTheCurrentPinAfterLeavingSettings() = runBlocking {
        val d = dir(); val clock = Clock()
        val lock = DesktopProfileLock(d, scope, { clock.now })
        lock.load(); setPin(lock, ana, "9753")
        lock.leftSettings()
        lock.beginRemove(ana)
        assertEquals(DesktopPinPurpose.CONFIRM_REMOVE, lock.state.value.prompt?.purpose, "turning it off asks for the current PIN")
        lock.submit("9753")
        await("removed") { lock.state.value.locks[ana.key] == null }
        // The password sign-in is the Forgot-PIN path: it may change the PIN without the old one.
        setPin(lock, ana, "1111"); lock.leftSettings()
        lock.provenByPassword(ana.key)
        lock.beginSetOrChange(ana)
        assertNull(lock.state.value.prompt); assertNotNull(lock.state.value.setup)
        lock.cancelSetup()
    }

    @Test fun setupRefusesShortAndMismatchedPins() = runBlocking {
        val lock = DesktopProfileLock(dir(), scope); lock.load()
        lock.viewerChanged(ana); await("unlocked") { lock.state.value.unlocked == ana.key }
        lock.beginSetOrChange(ana)
        lock.setupEnter("12"); assertEquals(DesktopPinNote.TOO_SHORT, lock.state.value.setup?.note)
        lock.setupEnter("1234"); lock.setupEnter("4321")
        assertEquals(DesktopPinNote.MISMATCH, lock.state.value.setup?.note)
        assertNull(lock.state.value.setup?.first)
        assertEquals("12345678", desktopPinInput("12a34-5678 99"))
    }

    @Test fun fiveMinutesAwayRelocksButPlayingInTheBackgroundDoesNot() = runBlocking {
        val d = dir(); val clock = Clock()
        val lock = DesktopProfileLock(d, scope, { clock.now })
        lock.load(); setPin(lock, ana, "8642")
        var relocks = 0; lock.onRelock = { relocks++ }
        // Unfocused while playing is the desktop's picture-in-picture: the time away doesn't run.
        lock.playing(true); lock.window(iconified = false, focused = false)
        clock.now += PROFILE_PIN_RELOCK_AFTER_MS + 1
        lock.window(focused = true)
        assertEquals(0, relocks); assertNull(lock.state.value.gate)
        // Minimized counts even while playing; under five minutes doesn't relock.
        lock.window(iconified = true)
        clock.now += PROFILE_PIN_RELOCK_AFTER_MS - 1
        lock.window(iconified = false)
        assertEquals(0, relocks)
        lock.playing(false); lock.window(focused = false)
        clock.now += PROFILE_PIN_RELOCK_AFTER_MS
        lock.window(focused = true)
        assertEquals(1, relocks)
        assertEquals(DesktopPinPurpose.GATE, lock.state.value.gate?.purpose, "back after five minutes counts as opening again")
        assertFalse(lock.admits(ana.key))
        assertTrue(desktopPinAway(iconified = true, focused = false, playing = true))
        assertFalse(desktopPinAway(iconified = false, focused = true, playing = false))
    }

    /** Leg 172 integration: music playing in an unfocused window is not time away, so the PIN doesn't return over it. */
    @Test fun musicPlayingInTheBackgroundIsNotTimeAway() = runBlocking {
        val d = dir(); val clock = Clock()
        val lock = DesktopProfileLock(d, scope, { clock.now })
        lock.load(); setPin(lock, ana, "8642")
        var relocks = 0; lock.onRelock = { relocks++ }
        val seerr = DesktopSeerr(DesktopSeerrStore(d), scope, { ana }, log = { })
        val s = DesktopState(account = ana, route = DesktopRoute.Home)
        desktopAccountsObserve(s, s, lock, seerr, musicPlaying = true)
        lock.window(iconified = false, focused = false)
        clock.now += PROFILE_PIN_RELOCK_AFTER_MS + 1
        lock.window(focused = true)
        assertEquals(0, relocks, "music counts as playing")
        desktopAccountsObserve(s, s, lock, seerr, musicPlaying = false)
        lock.window(focused = false)
        clock.now += PROFILE_PIN_RELOCK_AFTER_MS + 1
        lock.window(focused = true)
        assertEquals(1, relocks, "without video or music the time away runs")
    }

    @Test fun approvalCopyCoversEveryOutcomeWithoutServerText() {
        QuickConnectApprovalResult.entries.forEach { r ->
            val text = desktopApprovalText(r, "Ana")
            assertTrue(text.endsWith("."), "$r")
            assertFalse(Regex("\\b[45]\\d\\d\\b").containsMatchIn(text), "no raw status in $r")
        }
        assertTrue("Ana" in desktopApprovalText(QuickConnectApprovalResult.APPROVED, "Ana"))
    }

    @Test fun approvalRefusesABadCodeWithoutCallingTheServer() = runBlocking {
        val hits = CopyOnWriteArrayList<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex -> hits += ex.requestURI.path; ex.sendResponseHeaders(204, -1); ex.close() }
        http.start()
        try {
            val a = ana.copy(server = server.copy(url = "http://127.0.0.1:${http.address.port}"))
            val logs = CopyOnWriteArrayList<String>()
            val approval = DesktopQuickConnectApproval(QuickConnectApprovalClient(desktopSeerrHttp(), ClientIdentity("Pictureshow Desktop", "test-device", "0.1.0")), scope, { a }, log = { logs += it })
            approval.open(); approval.approve("no!")
            assertEquals(QuickConnectApprovalResult.INVALID_INPUT, approval.state.value.result)
            assertTrue(hits.isEmpty())
            approval.approve("AB12CD")
            await("answered") { approval.state.value.result != null && !approval.state.value.busy }
            assertTrue(logs.none { "AB12CD" in it }, "the code is never logged")
        } finally { http.stop(0) }
    }

    @Test fun seerrSecretIsEncryptedAndBoundToItsAccount() {
        val d = dir()
        val cipher = DesktopSeerrCipher(File(d, "seerr.key"))
        val sealed = cipher.encrypt(ana.key, "connect.sid=s%3Asecret")
        assertFalse("secret" in sealed)
        assertEquals("connect.sid=s%3Asecret", cipher.decrypt(ana.key, sealed))
        assertFails { cipher.decrypt(ben.key, sealed) }
        assertEquals(32, File(d, "seerr.key").length())
    }

    /** A Seerr stand-in: Jellyfin sign-in sets the session cookie; every call after it must carry that cookie. */
    private fun fakeSeerr(): Pair<HttpServer, CopyOnWriteArrayList<String>> {
        val seen = CopyOnWriteArrayList<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        http.createContext("/api/v1/") { ex ->
            val path = ex.requestURI.path.removePrefix("/api/v1/")
            seen += "${ex.requestMethod} $path ${ex.requestHeaders.getFirst("Authorization") ?: ""}${ex.requestHeaders.getFirst("X-Emby-Token") ?: ""}"
            val authed = ex.requestHeaders.getFirst("Cookie")?.contains("connect.sid=good") == true
            when {
                path == "auth/jellyfin" -> {
                    val body = ex.requestBody.readBytes().decodeToString()
                    if ("\"password\":\"right\"" in body) { ex.responseHeaders.add("Set-Cookie", "connect.sid=good; Path=/; HttpOnly"); reply(ex, 200, """{"id":7,"displayName":"Ana","permissions":32}""") }
                    else reply(ex, 401, """{"message":"Unauthorized"}""")
                }
                !authed -> reply(ex, 403, """{"message":"Unauthorized"}""")
                path == "auth/me" -> reply(ex, 200, """{"id":7,"displayName":"Ana","permissions":32}""")
                path.startsWith("search") -> reply(ex, 200, """{"page":1,"totalPages":1,"results":[
                    {"id":11,"mediaType":"movie","title":"Owned Film","releaseDate":"2001-01-01"},
                    {"id":12,"mediaType":"movie","title":"Missing Film","releaseDate":"2002-01-01"},
                    {"id":13,"mediaType":"person","name":"Someone"},
                    {"id":14,"mediaType":"tv","name":"Ready Show","firstAirDate":"2003-01-01","mediaInfo":{"tmdbId":14,"mediaType":"tv","status":5}}]}""")
                path == "tv/20" -> reply(ex, 200, """{"id":20,"name":"Long Show","seasons":[{"seasonNumber":0,"episodeCount":2},{"seasonNumber":1,"episodeCount":8},{"seasonNumber":2,"episodeCount":8},{"seasonNumber":3,"episodeCount":8}],
                    "mediaInfo":{"tmdbId":20,"mediaType":"tv","status":3,"seasons":[{"seasonNumber":1,"status":5}]}}""")
                path == "request" && ex.requestMethod == "POST" -> {
                    seen += "BODY " + ex.requestBody.readBytes().decodeToString()
                    reply(ex, 201, """{"id":99,"status":1,"type":"tv","media":{"tmdbId":20,"mediaType":"tv","status":2}}""")
                }
                else -> reply(ex, 404, "{}")
            }
        }
        http.start()
        return http to seen
    }

    @Test fun seerrConnectsSavesLocallyAndRequestsChosenSeasons() = runBlocking {
        val (http, seen) = fakeSeerr()
        try {
            val d = dir(); val url = "http://127.0.0.1:${http.address.port}"
            val store = DesktopSeerrStore(d)
            val logs = CopyOnWriteArrayList<String>()
            val seerr = DesktopSeerr(store, scope, { ana }, log = { logs += it }, searchDelayMillis = 0)
            seerr.viewerChanged(ana)
            seerr.openConnect()
            seerr.connect(url, null, "Ana", "wrong") { true }
            await("refused") { !seerr.state.value.connecting && seerr.state.value.connectError != null }
            assertEquals(SeerrError.PASSWORD, seerr.state.value.connectError)
            assertFalse(seerr.state.value.connected)
            seerr.connect(url, null, "Ana", "right") { true }
            await("connected") { seerr.state.value.connected }
            assertEquals("Ana", seerr.state.value.config?.user?.name)
            assertFalse(seerr.state.value.connectOpen)
            val saved = File(d, "seerr.json").readText()
            assertFalse("good" in saved || "right" in saved, "cookie and password are sealed: $saved")
            assertTrue(logs.none { "right" in it || "good" in it })
            assertTrue(seen.none { "token-ana" in it }, "the Jellyfin token never goes to Seerr")

            // SEERR-07: the offers row leaves out people, titles Seerr says are available, and what the library found.
            seerr.searchOffers("film")
            await("offers") { seerr.state.value.offersTerm == "film" }
            val offers = seerrSearchOffers(seerr.state.value.offers, listOf("Owned Film" to 2001))
            assertEquals(listOf(12), offers.map { it.id })

            // SEERR-06: taken seasons can't be chosen; specials are never offered; only chosen seasons are sent.
            seerr.openTitle("tv", 20)
            await("title") { seerr.state.value.title?.id == 20 && !seerr.state.value.titleLoading }
            assertEquals(setOf(2, 3), seerr.state.value.chosen)
            seerr.toggleSeason(1); seerr.toggleSeason(3)
            assertEquals(setOf(2), seerr.state.value.chosen)
            seerr.request()
            await("sent") { seerr.state.value.sent }
            val body = seen.first { it.startsWith("BODY ") }
            assertTrue("\"seasons\":[2]" in body.replace(" ", ""), body)

            // Another viewer sees none of Ana's Seerr; signing Ana out drops her connection.
            seerr.viewerChanged(ben)
            assertFalse(seerr.state.value.connected)
            assertNull(seerr.state.value.title)
            store.retain(setOf(ben.key))
            assertNull(store.client(ana.key))
        } finally { http.stop(0) }
    }

    @Test fun seerrConnectionIsNotSavedForAnAccountSignedOutMeanwhile() = runBlocking {
        val (http, _) = fakeSeerr()
        try {
            val store = DesktopSeerrStore(dir())
            val failure = assertFailsWith<SeerrFailure> { store.connect(ana, "http://127.0.0.1:${http.address.port}", null, "Ana", "right") { false } }
            assertEquals(SeerrError.EXPIRED, failure.reason)
            assertTrue(store.connections.value.isEmpty())
        } finally { http.stop(0) }
    }

    @Test fun informationIsMeasuredAndHonest() = runBlocking {
        val decoders = """[{"codec":"h264","driver":"h264","description":"H.264"},{"codec":"hevc","driver":"hevc","description":"HEVC"},
            {"codec":"aac","driver":"aac","description":"AAC"},{"codec":"flac","driver":"flac","description":"FLAC"},{"codec":"ass","driver":"ass","description":"subs"}]"""
        val info = DesktopInformation(scope, { DesktopSettings() }, probe = { keys ->
            keys.associateWith { k -> mapOf("mpv-version" to "mpv 0.42.0", "ffmpeg-version" to "8.1", "mpv-configuration" to "-Dgpl=false -Dlibmpv=true", "decoder-list" to decoders, "hwdec" to "no")[k] }
        }, catalog = { listOf(DesktopLicense("x:y", "Y", "1", "MIT", "text")) })
        info.loadReport()
        await("report") { info.state.value.report != null && !info.state.value.reportLoading }
        val report = info.state.value.report!!
        assertEquals(listOf("h264", "hevc"), report.videoDecoders.map { it.codec })
        assertEquals(listOf("aac", "flac"), report.audioDecoders.map { it.codec })
        assertEquals(true, report.mpvLgpl)
        assertTrue(report.profileJson!!.contains("Pictureshow Desktop libmpv"))
        assertTrue("H.264, HEVC" in desktopComputerVerdict(report))
        assertNull(desktopMpvLgpl("-Dlibmpv=true"), "no claim when the build doesn't say")
        assertEquals(false, desktopMpvLgpl("-Dgpl=true"))

        val missing = DesktopInformation(scope, { DesktopSettings() }, probe = { throw UnsatisfiedLinkError("no mpv") })
        missing.loadReport()
        await("missing") { missing.state.value.report != null }
        assertNull(missing.state.value.report!!.mpvVersion)
        assertTrue("couldn't load libmpv" in desktopComputerVerdict(missing.state.value.report!!))

        info.loadLicenses(); await("licenses") { info.state.value.licenses != null }
        info.openLicense("x:y"); assertEquals("Y", info.state.value.license?.name)
    }

    /** INFO-04: the generated catalog is on the classpath, complete, and honest about libmpv not being bundled. */
    @Test fun generatedLicenseCatalogListsRuntimeLibrariesAndLibmpv() {
        val rows = assertNotNull(desktopLicenseCatalog(), "licenses/catalog.json is generated into the resources")
        assertTrue(rows.size > 20)
        assertTrue(rows.all { it.text.length > 200 && it.license.isNotBlank() }, "every row has a license and its full text")
        val ids = rows.map { it.id }.toSet()
        assertTrue("org.jetbrains.kotlin:kotlin-stdlib" in ids)
        assertTrue(ids.any { it.startsWith("io.ktor:") })
        val mpv = rows.single { it.id == "mpv-system" }
        assertTrue("does not include mpv or FFmpeg" in mpv.text)
        assertTrue("GNU LESSER GENERAL PUBLIC LICENSE" in mpv.text)
        assertTrue(desktopOwnLicense().orEmpty().startsWith("Mozilla Public License Version 2.0"))
    }

    @Test fun supporterPageNeverOffersAnOutsidePaymentRoute() {
        val source = File("src/main/kotlin/app/pictureshow/desktop/DesktopInformationPages.kt").readText()
        listOf("ko-fi", "kofi", "sponsors", "patreon", "paypal").forEach { assertFalse(it in source.lowercase(), it) }
        assertTrue("isn't available" in DESKTOP_SUPPORTER_NO_PURCHASE)
    }
}
