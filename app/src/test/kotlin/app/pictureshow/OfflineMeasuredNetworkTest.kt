/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Every "no connection" and "mobile data" line starts here. A wired TV with the server gone has a network, and saying
 * otherwise would also stop a home server with no internet from working, so "validated" is deliberately not asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OfflineMeasuredNetworkTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private fun network(vararg transports: Int, internet: Boolean = true, notMetered: Boolean = false): OfflineNetwork {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(capabilities).addTransportType(it) }
        if (internet) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (notMetered) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(manager).setNetworkCapabilities(requireNotNull(manager.activeNetwork), capabilities)
        return measuredNetwork(context)
    }

    @Test fun `job network rather than default network determines policy and sockets`() {
        network(NetworkCapabilities.TRANSPORT_WIFI)
        val selected = ShadowNetwork.newInstance(134)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(selected, capabilities)
        assertEquals(OfflineNetwork.unmetered, measuredNetwork(context))
        assertEquals(OfflineNetwork.metered, measuredNetwork(context, selected))
        val base = okhttp3.OkHttpClient()
        val routed = offlineNetworkClient(base, selected)
        org.junit.Assert.assertSame(selected.socketFactory, routed.socketFactory)
        org.junit.Assert.assertNotSame(base.connectionPool, routed.connectionPool)
        org.junit.Assert.assertNotSame(base.dns, routed.dns)
    }

    @Test fun `no active network is none`() {
        shadowOf(manager).setDefaultNetworkActive(false)
        assertEquals(OfflineNetwork.none, measuredNetwork(context))
    }

    @Test fun `a network that can't reach anything is none`() {
        assertEquals(OfflineNetwork.none, network(NetworkCapabilities.TRANSPORT_WIFI, internet = false))
    }

    @Test fun `a cable is unmetered, even with the server gone and no validation`() {
        assertEquals(OfflineNetwork.unmetered, network(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    @Test fun `Wi-Fi is unmetered, so the offer names no mobile data`() {
        assertEquals(OfflineNetwork.unmetered, network(NetworkCapabilities.TRANSPORT_WIFI))
    }

    @Test fun `cellular is metered unless it says it isn't`() {
        assertEquals(OfflineNetwork.metered, network(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(OfflineNetwork.unmetered, network(NetworkCapabilities.TRANSPORT_CELLULAR, notMetered = true))
    }
}
