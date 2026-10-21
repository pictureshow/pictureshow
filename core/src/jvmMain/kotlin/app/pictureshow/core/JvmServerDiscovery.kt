package app.pictureshow.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class JvmServerDiscovery(private val durationMillis: Long = 3_000) : ServerDiscovery {
    override suspend fun discover(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredServer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val payload = "Who is JellyfinServer?".toByteArray(Charsets.US_ASCII)
            val addresses = buildSet {
                add(InetAddress.getByName("255.255.255.255"))
                NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
                    network.interfaceAddresses.mapNotNull { it.broadcast }.forEach(::add)
                }
            }
            addresses.forEach { address ->
                currentCoroutineContext().ensureActive()
                try {
                    socket.send(DatagramPacket(payload, payload.size, address, 7359))
                } catch (_: java.io.IOException) {
                    // An interface can disappear or deny broadcast while another remains usable.
                }
            }
            val deadline = System.nanoTime() + durationMillis * 1_000_000
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(ByteArray(8_192), 8_192)
                try {
                    socket.receive(packet)
                    val parsed = DiscoveryReplies.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                    if (parsed != null) found.putIfAbsent(parsed.id, parsed)
                } catch (_: SocketTimeoutException) {
                    // Short receive timeouts allow coroutine cancellation to close the socket promptly.
                }
            }
        }
        found.values.toList()
    }
}
