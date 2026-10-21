package app.pictureshow.core

import io.ktor.http.Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ServerDiscovery {
    suspend fun discover(): List<DiscoveredServer>
}

@Serializable
data class DiscoveredServer(
    @SerialName("Address") val address: String,
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("EndpointAddress") val endpointAddress: String? = null
)

/** A reply is only a discovery hint; authenticate only after probing its advertised address. */
object DiscoveryReplies {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(packet: String): DiscoveredServer? = try {
        json.decodeFromString<DiscoveredServer>(packet).takeIf {
            val url = Url(it.address)
            it.id.isNotBlank() &&
                it.name.isNotBlank() &&
                url.host.isNotBlank() &&
                url.protocol.name in listOf("http", "https") &&
                url.user == null &&
                url.password == null
        }
    } catch (_: Exception) {
        null
    }

    fun collect(packets: Iterable<String>): List<DiscoveredServer> = packets.mapNotNull(::parse).distinctBy { it.id }
}
