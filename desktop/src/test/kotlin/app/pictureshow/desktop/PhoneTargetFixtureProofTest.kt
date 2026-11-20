package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.control.RemoteClient
import app.pictureshow.core.control.RemoteNowPlaying
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class PhoneTargetFixtureProofTest {
    @Test
    fun desktopControlsRealPhoneReceiver() = runBlocking {
        val fixture = Path.of(requireNotNull(System.getenv("PICTURESHOW_REMOTE_FIXTURE_DIR")))
        val json = Json { ignoreUnknownKeys = true }
        val stored = json.parseToJsonElement(Files.readString(fixture.resolve("desktop-data/accounts.json")))
        val account = json.decodeFromJsonElement(Account.serializer(), stored.jsonObject.getValue("accounts").jsonArray.first())
        require(account.server.url == "http://127.0.0.1:18097" && account.server.name == "Pictureshow Remote 172 Disposable")
        val phone = Files.readString(fixture.resolve("phone-device-id")).trim()
        val title = Files.readString(fixture.resolve("item-id")).trim()
        val http = HttpClient(OkHttp) { expectSuccess = false; followRedirects = false }
        val client = RemoteClient(http, ClientIdentity("Pictureshow Desktop", requireNotNull(account.deviceId)))
        val controller = RemoteControllerHost(this, object : RemoteControllerGateway {
            override suspend fun targets(account: Account): ClientResult<List<RemoteTarget>> = client.targets(account)
            override suspend fun send(account: Account, request: RemoteRequest): Int = client.send(account, request)
        }) { System.nanoTime() / 1_000_000 }

        suspend fun observed(label: String, matches: (RemoteNowPlaying?) -> Boolean) {
            withTimeout(40_000) {
                controller.state.first { state ->
                    val connected = state.controller
                    connected?.phase == "attached" && connected.command?.fresh == true &&
                        connected.target?.device == phone && matches(connected.target?.now)
                }
            }
            println("PASS Desktop $label")
        }
        try {
            controller.bind(account)
            controller.setForeground(true)
            controller.setPicker(true)
            val devices = withTimeout(40_000) { controller.state.first { state -> state.targets.any { it.device == phone } } }
            val target = devices.targets.single { it.device == phone }
            require(target.sameViewer && target.now == null)
            controller.attach(target)
            controller.setPicker(false)
            observed("attached to idle phone") { it == null }
            controller.playNow(listOf(title), 120_000_000)
            observed("Play on opened the requested title from 12 seconds") {
                it != null && it.item == title && it.canSeek && !it.paused && it.positionTicks >= 110_000_000
            }
            controller.pause()
            observed("Pause reached phone playback") { it != null && it.item == title && it.paused }
            controller.seekTo(450_000_000)
            observed("Seek moved paused playback to 45 seconds") {
                it != null && it.item == title && it.paused && abs(it.positionTicks - 450_000_000) <= 10_000_000
            }
            controller.stop()
            observed("Stop cleared the phone playback") { it == null }
        } finally {
            controller.setPicker(false)
            controller.setForeground(false)
            http.close()
        }
    }
}
