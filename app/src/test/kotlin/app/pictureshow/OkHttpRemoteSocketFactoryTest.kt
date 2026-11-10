/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule

class OkHttpRemoteSocketFactoryTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    @Test fun headerAuthenticatesAndMessagesFlowBothWays() {
        val server = MockWebServer()
        val fromClient = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("hello")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        fromClient.put(text)
                    }
                }
            )
        )
        server.start()
        try {
            val received = LinkedBlockingQueue<String>()
            val closed = CountDownLatch(1)
            val socket = OkHttpRemoteSocketFactory().open(
                "ws://${server.hostName}:${server.port}/jf/socket",
                "MediaBrowser Token=\"t\"",
                object : RemoteSocketEvents {
                    override fun opened() = Unit
                    override fun message(text: String) = received.put(text)
                    override fun closed() = closed.countDown()
                }
            )
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/jf/socket", request.path)
            assertEquals("MediaBrowser Token=\"t\"", request.getHeader("Authorization"))
            assertEquals("hello", received.poll(5, TimeUnit.SECONDS))
            socket.send("""{"MessageType":"KeepAlive"}""")
            assertEquals("""{"MessageType":"KeepAlive"}""", fromClient.poll(5, TimeUnit.SECONDS))
            socket.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally {
            server.shutdown()
        }
    }
}
