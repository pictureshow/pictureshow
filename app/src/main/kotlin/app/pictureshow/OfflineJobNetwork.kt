/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.net.Network
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/** Routes only offline requests; never bind the process and disturb a player using another network. */
internal class OfflineJobNetwork {
    @Volatile var network: Network? = null
    var owner: Any? = null

    fun calls(base: OkHttpClient): Call.Factory = object : Call.Factory {
        private var routedNetwork: Network? = null
        private var routedClient: OkHttpClient? = null

        @Synchronized
        override fun newCall(request: Request): Call {
            val selected = network ?: return base.newCall(request)
            if (selected != routedNetwork) {
                routedClient?.connectionPool?.evictAll()
                routedClient = offlineNetworkClient(base, selected)
                routedNetwork = selected
            }
            return requireNotNull(routedClient).newCall(request)
        }
    }
}

internal fun offlineNetworkClient(base: OkHttpClient, network: Network): OkHttpClient = base.newBuilder()
    .socketFactory(network.socketFactory)
    .dns { host -> network.getAllByName(host).toList() }
    .connectionPool(ConnectionPool())
    .build()
