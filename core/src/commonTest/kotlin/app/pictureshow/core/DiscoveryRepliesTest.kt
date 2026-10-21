package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscoveryRepliesTest {
    @Test
    fun replyPreservesBasePathAndOptionalEndpoint() {
        assertEquals(
            DiscoveredServer("http://example.test:8096/jellyfin", "server-1", "Family media", "192.0.2.2"),
            DiscoveryReplies.parse(reply(extra = """, "EndpointAddress":"192.0.2.2", "FutureField":true"""))
        )
        assertNull(DiscoveryReplies.parse(reply())?.endpointAddress)
    }

    @Test
    fun malformedAndUnsafeRepliesAreIgnored() {
        listOf(
            "", "not json", "[]", "{}", "{\"Address\":null}",
            reply(id = ""), reply(name = ""), reply(address = "ftp://example.test"),
            reply(address = "http://user:password@example.test")
        ).forEach { packet -> assertNull(DiscoveryReplies.parse(packet), packet) }
    }

    @Test
    fun collectionDeduplicatesByServerIdAndRetainsDifferentServers() {
        val first = reply()
        val secondAddress = reply(address = "https://example.test")
        val differentServer = reply(id = "server-2", name = "Second media")
        assertEquals(
            listOf(DiscoveryReplies.parse(first), DiscoveryReplies.parse(differentServer)),
            DiscoveryReplies.collect(listOf("bad packet", first, secondAddress, differentServer, first))
        )
    }

    private fun reply(
        address: String = "http://example.test:8096/jellyfin",
        id: String = "server-1",
        name: String = "Family media",
        extra: String = ""
    ): String = """{"Address":"$address","Id":"$id","Name":"$name"$extra}"""
}
