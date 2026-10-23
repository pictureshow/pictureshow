/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.Server
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class ApiHttpClientEngineTest {
    private val identity = ClientIdentity("Test TV", "transport-test")
    private val auth = """{"ServerId":"s","AccessToken":"test-token","User":{"Id":"u","Name":"Viewer"}}"""

    @Test
    fun actualAppOkHttpAllowsSlowHeadersAndSlowBody() = runBlocking {
        MockWebServer().use { fixture ->
            fixture.start()
            fixture.enqueue(MockResponse().setBody(auth).setHeadersDelay(6, TimeUnit.SECONDS))
            fixture.enqueue(MockResponse().setBody(auth).setBodyDelay(6, TimeUnit.SECONDS))
            createApiHttpClient().use { http ->
                val client = JellyfinClient(http, identity)
                val server = Server("s", "Test", fixture.url("/").toString(), "10.10.7")
                repeat(2) { assertIs<ClientResult.Success<*>>(client.authenticate(server, "Viewer", "test")) }
            }
            assertEquals(2, fixture.requestCount)
        }
    }

    @Test
    fun okHttpPassesThreeSecondBoundToConnectAndReturnsAnActionableError() = runBlocking {
        val timeouts = mutableListOf<Int>()
        // Deterministic unreachable peer: exercise OkHttp's actual Socket.connect timeout argument
        // without depending on the network's routing/firewall behavior for an unused address.
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    timeouts += timeout
                    Thread.sleep(timeout.toLong())
                    throw SocketTimeoutException("simulated unreachable peer")
                }
            }
            override fun createSocket(host: String?, port: Int): Socket = error("unused")
            override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress?, port: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = error("unused")
        }
        HttpClient(OkHttp) {
            configureApiHttpClient()
            engine {
                config {
                    socketFactory(sockets)
                    proxy(Proxy.NO_PROXY)
                    retryOnConnectionFailure(false)
                }
            }
        }.use { http ->
            val start = System.nanoTime()
            val result = JellyfinClient(http, identity).authenticate(
                Server("s", "Test", "http://192.0.2.1:8096", "10.10.7"),
                "Viewer",
                "test"
            )
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(listOf(3_000), timeouts)
            assertTrue(elapsed in 2_900L..8_000L, "Connect took $elapsed ms")
            val error = assertIs<ClientResult.Failure>(result).error
            assertEquals(ErrorKind.NETWORK, error.kind)
            assertTrue(error.message.contains("try again"))
        }
    }
}
