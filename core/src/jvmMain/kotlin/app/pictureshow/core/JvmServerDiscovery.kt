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

/** [port] and [targets] exist for tests; the defaults broadcast Jellyfin's discovery request on every network. */
class JvmServerDiscovery(
    private val durationMillis: Long = 3_000,
    private val port: Int = 7359,
    private val targets: (() -> Set<InetAddress>)? = null
) : ServerDiscovery {
    override suspend fun discover(): List<DiscoveredServer> = discover {}

    override suspend fun discover(onFound: (DiscoveredServer) -> Unit): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredServer>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 200
            val payload = "Who is JellyfinServer?".toByteArray(Charsets.US_ASCII)
            val addresses = targets?.invoke() ?: buildSet {
                add(InetAddress.getByName("255.255.255.255"))
                NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
                    network.interfaceAddresses.mapNotNull { it.broadcast }.forEach(::add)
                }
            }
            addresses.forEach { address ->
                currentCoroutineContext().ensureActive()
                try {
                    socket.send(DatagramPacket(payload, payload.size, address, port))
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
                    // A server answers once per interface it hears on; report it the first time only.
                    if (parsed != null && found.putIfAbsent(parsed.id, parsed) == null) onFound(parsed)
                } catch (_: SocketTimeoutException) {
                    // Short receive timeouts allow coroutine cancellation to close the socket promptly.
                }
            }
        }
        found.values.toList()
    }
}
