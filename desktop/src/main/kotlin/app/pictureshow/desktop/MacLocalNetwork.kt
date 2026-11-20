/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI

internal const val MAC_LOCAL_NETWORK_HINT = "Pictureshow needs Local Network access to connect to this server. " +
    "Allow Pictureshow in System Settings > Privacy & Security > Local Network, then try again."

internal fun macLocalNetworkHint(url: String, failure: Throwable, os: String = System.getProperty("os.name")): String? {
    if (!os.startsWith("Mac")) return null
    val host = runCatching { URI(url).host }.getOrNull()?.trimEnd('.')?.removeSurrounding("[", "]") ?: return null
    if (!isLocalServerHost(host)) return null
    val causes = generateSequence(failure) { it.cause }.take(16)
    return MAC_LOCAL_NETWORK_HINT.takeIf {
        causes.any { cause -> cause is NoRouteToHostException ||
            (cause is SocketException && macLocalNetworkSocketError.containsMatchIn(cause.message.orEmpty())) }
    }
}

private val macLocalNetworkSocketError = Regex(
    "no route to host|operation not permitted|permission denied|EHOSTUNREACH|EPERM|EACCES", RegexOption.IGNORE_CASE
)

private fun isLocalServerHost(host: String): Boolean {
    if (host.endsWith(".local", ignoreCase = true)) return true
    if (':' in host) {
        // A numeric IPv6 literal entails no DNS lookup, including for a .local failure.
        val address = runCatching { InetAddress.getByName(host.substringBefore('%')) }.getOrNull() ?: return false
        return address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && address.address[0].toInt() and 0xfe == 0xfc)
    }
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (first, second) = octets
    return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
        (first == 169 && second == 254)
}
