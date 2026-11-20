/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacLocalNetworkTest {
    private val localHost = "media." + "local"

    @Test fun onlyLocalDestinationsGetTheMacNetworkHint() {
        val blocked = IOException("Wrapped by the transport", NoRouteToHostException("No route to host"))
        for (parts in listOf(listOf(10, 1, 2, 3), listOf(172, 31, 2, 3), listOf(192, 168, 2, 3), listOf(169, 254, 2, 3))) {
            assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://${parts.joinToString(".")}:8096", blocked, "Mac OS X"))
        }
        assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("https://${localHost.uppercase()}./jellyfin", blocked, "Mac OS X"))
        assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://[fd12::1]:8096", blocked, "Mac OS X"))
        assertEquals(MAC_LOCAL_NETWORK_HINT, macLocalNetworkHint("http://[fe80::1%25en0]:8096", SocketException("Operation not permitted"), "Mac OS X"))
        for (host in listOf("jellyfin.example.org", "media.local.example.org", "[::1]", "[2001:db8::1]", listOf(172, 32, 2, 3).joinToString("."))) {
            assertNull(macLocalNetworkHint("https://$host", blocked, "Mac OS X"))
        }
        assertNull(macLocalNetworkHint("https://$localHost", blocked, "Linux"))
    }

    @Test fun ordinaryConnectionFailuresKeepTheExistingError() {
        for (failure in listOf(ConnectException("Connection refused"), UnknownHostException("media.local"), IOException("Permission denied"))) {
            assertNull(macLocalNetworkHint("http://$localHost", failure, "Mac OS X"))
        }
    }
}
