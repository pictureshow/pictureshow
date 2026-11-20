/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAC_LOCAL_NETWORK_HINT = "Pictureshow needs Local Network access to connect to this server. " +
    "Allow Pictureshow in System Settings > Privacy & Security > Local Network, then try again."

internal suspend fun macLocalNetworkHint(
    url: String,
    failure: Throwable,
    os: String = System.getProperty("os.name"),
    resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName
): String? {
    if (!os.startsWith("Mac")) return null
    val blocked = generateSequence(failure) { it.cause }.take(16).any { cause ->
        cause is NoRouteToHostException ||
            (cause is SocketException && macLocalNetworkSocketError.containsMatchIn(cause.message.orEmpty()))
    }
    if (!blocked) return null
    val host = runCatching { URI(url).host }.getOrNull()?.removeSurrounding("[", "]") ?: return null
    return MAC_LOCAL_NETWORK_HINT.takeIf { isLocalServerHost(host, resolve) }
}

private val macLocalNetworkSocketError = Regex(
    "no route to host|operation not permitted|permission denied|EHOSTUNREACH|EPERM|EACCES", RegexOption.IGNORE_CASE
)

private suspend fun isLocalServerHost(host: String, resolve: (String) -> Array<InetAddress>): Boolean {
    val unqualified = host.trimEnd('.')
    if (unqualified.endsWith(".local", ignoreCase = true)) return true
    if (':' in host) {
        // With the zone suffix removed, this is a numeric IPv6 literal.
        val address = runCatching { InetAddress.getByName(host.substringBefore('%')) }.getOrNull() ?: return false
        return address.isLocalServerAddress()
    }
    val octets = unqualified.split('.').map(String::toIntOrNull)
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        return InetAddress.getByAddress(octets.map { it!!.toByte() }.toByteArray()).isLocalServerAddress()
    }
    return withContext(Dispatchers.IO) {
        try {
            resolve(host).any { it.isLocalServerAddress() }
        } catch (_: UnknownHostException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private fun InetAddress.isLocalServerAddress(): Boolean =
    isSiteLocalAddress || isLinkLocalAddress || (address.size == 16 && address[0].toInt() and 0xfe == 0xfc)
