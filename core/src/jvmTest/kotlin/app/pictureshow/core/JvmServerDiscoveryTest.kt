/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Real UDP on loopback: each server is reported as it answers, once, well before the search window closes. */
class JvmServerDiscoveryTest {
    private fun reply(id: String, name: String) = """{"Address":"http://example.test:8096","Id":"$id","Name":"$name"}"""

    @Test
    fun serversAreReportedAsTheyAnswerAndOnlyOnce() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { responder ->
            val answering = thread {
                val request = DatagramPacket(ByteArray(256), 256)
                responder.receive(request)
                fun send(packet: String) {
                    val bytes = packet.toByteArray()
                    responder.send(DatagramPacket(bytes, bytes.size, request.socketAddress))
                }
                // The household server answers at once, and again on a second interface; another arrives later.
                send(reply("server-1", "Family media"))
                send(reply("server-1", "Family media"))
                Thread.sleep(400)
                send(reply("server-2", "Second media"))
            }
            val started = System.nanoTime()
            val reported = mutableListOf<Pair<String, Long>>()
            val found = JvmServerDiscovery(durationMillis = 2_500, port = responder.localPort, targets = { setOf(loopback) })
                .discover { reported += it.id to (System.nanoTime() - started) / 1_000_000 }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            answering.join()
            assertEquals(listOf("server-1", "server-2"), reported.map { it.first })
            assertEquals(listOf("server-1", "server-2"), found.map { it.id })
            // Reported long before the window closed, not all at once at its end.
            assertTrue(reported.first().second < 1_000, "first server reported at ${reported.first().second} ms")
            assertTrue(reported.first().second < reported.last().second, "reports $reported")
            assertTrue(elapsed >= 2_400, "the window still runs to its end: $elapsed ms")
        }
    }

    @Test
    fun anEmptyNetworkReportsNothingAndReturnsAnEmptyList() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { silent ->
            val reported = mutableListOf<DiscoveredServer>()
            val found = JvmServerDiscovery(durationMillis = 500, port = silent.localPort, targets = { setOf(loopback) })
                .discover { reported += it }
            assertEquals(emptyList(), reported)
            assertEquals(emptyList(), found)
        }
    }
}
