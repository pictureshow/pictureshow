/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update

/**
 * Touch Home and Libraries: the way to this viewer's ready downloads, whatever else the page shows. Home decides
 * where it goes (HOME-09): beside "Showing what was loaded earlier", or first when nothing else is left.
 */
@Composable
internal fun rememberTouchHomeDownloads(accountKey: String, open: () -> Unit): (() -> Unit)? {
    val engine = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<OfflineEngine>() } ?: return null
    val libraries by engine.libraries.collectAsState()
    return open.takeIf { libraries[accountKey].hasReadyDownloads() }
}

/** How long the set of networks must hold still before it counts as a change; a handover reports several steps. */
internal const val TOUCH_NETWORK_SETTLE_MILLIS = 750L

/**
 * The settled networks a page is watching, after the first: a network arriving, leaving or being replaced (Wi-Fi to
 * mobile data) is a moment the server's reach may have changed. The first settled set is where the page started and
 * isn't a change, so opening a page never reads twice.
 */
@OptIn(FlowPreview::class)
internal fun <T> Flow<Set<T>>.settledNetworkChanges(settleMillis: Long = TOUCH_NETWORK_SETTLE_MILLIS): Flow<Set<T>> =
    debounce(settleMillis).distinctUntilChanged().drop(1)

/** Calls [onChange] on the main thread when the device's networks change while this is composed (HOME-09). */
@Composable
internal fun TouchNetworkChanges(onChange: () -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onChange)
    val networks = remember { MutableStateFlow<Set<String>>(emptySet()) }
    DisposableEffect(context) {
        val callback = registerInternetNetworks(context, networks)
        onDispose {
            callback?.let { runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        }
    }
    LaunchedEffect(networks) { networks.settledNetworkChanges().collect { latest() } }
}

private fun registerInternetNetworks(context: Context, networks: MutableStateFlow<Set<String>>): ConnectivityManager.NetworkCallback? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networks.update { it + network.toString() }

        override fun onLost(network: Network) = networks.update { it - network.toString() }
    }
    return runCatching {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(request, callback)
        callback
    }.getOrNull()
}
