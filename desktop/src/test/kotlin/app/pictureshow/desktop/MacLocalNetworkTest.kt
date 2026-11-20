/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

class MacLocalNetworkTest {
    private val localHost = "media." + "local"

    @Test fun onlyLocalDestinationsGetTheMacNetworkHint() = runBlocking<Unit> {
        val blocked = IOException("Wrapped by the transport", NoRouteToHostException("No route to host"))
        for (parts in listOf(listOf(10, 1, 2, 3), listOf(172, 31, 2, 3), listOf(192, 168, 2, 3), listOf(169, 254, 2, 3))) {
            assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://${parts.joinToString(".")}:8096", blocked, "Mac OS X"))
        }
        assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("https://${localHost.uppercase()}./jellyfin", blocked, "Mac OS X"))
        assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://[fd12::1]:8096", blocked, "Mac OS X"))
        assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://[fe80::1%25en0]:8096", SocketException("Operation not permitted"), "Mac OS X"))
        for (host in listOf("jellyfin.example.org", "media.local.example.org", "[::1]", "[2001:db8::1]", listOf(172, 32, 2, 3).joinToString("."))) {
            assertNull(macLocalNetworkHint("https://$host", blocked, "Mac OS X") { emptyArray() })
        }
        assertNull(macLocalNetworkHint("https://$localHost", blocked, "Linux"))
    }

    @Test fun ordinaryConnectionFailuresKeepTheExistingError() = runBlocking<Unit> {
        for (failure in listOf(ConnectException("Connection refused"), UnknownHostException("media.local"), IOException("Permission denied"))) {
            assertNull(macLocalNetworkHint("http://media", failure, "Mac OS X") { error("Unexpected DNS lookup") })
        }
    }

    @Test fun resolvesNamedServersOffTheSwingThread() = runBlocking<Unit> {
        val blocked = NoRouteToHostException()
        withContext(Dispatchers.Swing) {
            assertTrue(SwingUtilities.isEventDispatchThread())
            for (address in listOf(
                InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3)),
                InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 2, 3)),
                InetAddress.getByName("fd12::1"),
                InetAddress.getByName("fe80::1")
            )) {
                assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://media:8096", blocked, "Mac OS X") { host ->
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    assertEquals("media", host)
                    arrayOf(address)
                })
            }
        }
    }

    @Test fun publicUnknownAndNonMacNamedServersKeepTheExistingError() = runBlocking<Unit> {
        val blocked = NoRouteToHostException()
        assertNull(macLocalNetworkHint("https://media.example.org.", blocked, "Mac OS X") { host ->
            assertEquals("media.example.org.", host)
            arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), InetAddress.getLoopbackAddress())
        })
        assertNull(macLocalNetworkHint("https://unresolved", blocked, "Mac OS X") { throw UnknownHostException(it) })
        for (os in listOf("Linux", "Windows")) {
            assertNull(macLocalNetworkHint("http://media", blocked, os) { error("Unexpected DNS lookup") })
        }
    }
}
