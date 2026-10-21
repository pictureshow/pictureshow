package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AddressCandidatesTest {
    @Test
    fun ipWithoutSchemeTriesHttpsAndHttpWithStandardAndJellyfinPorts() {
        assertEquals(
            listOf("https://10.0.0.5", "https://10.0.0.5:8920", "http://10.0.0.5", "http://10.0.0.5:8096"),
            AddressCandidates.build("10.0.0.5"),
        )
    }

    @Test
    fun hostnameWithoutSchemeTriesBothProtocols() {
        assertEquals(
            listOf("https://jellyfin.example.com", "https://jellyfin.example.com:8920", "http://jellyfin.example.com", "http://jellyfin.example.com:8096"),
            AddressCandidates.build("jellyfin.example.com"),
        )
    }

    @Test
    fun explicitSchemePortAndTrailingSlashAreRespected() {
        assertEquals(listOf("http://host:8096"), AddressCandidates.build(" http://host:8096/ "))
        assertEquals(listOf("https://host:8443/jellyfin"), AddressCandidates.build("https://host:8443/jellyfin/"))
    }

    @Test
    fun basePathIsKeptInEveryCandidate() {
        assertEquals(
            listOf("https://host/jellyfin", "https://host:8920/jellyfin", "http://host/jellyfin", "http://host:8096/jellyfin"),
            AddressCandidates.build("host/jellyfin"),
        )
    }

    @Test
    fun explicitSchemeWithoutPortUsesOnlyThatScheme() {
        assertEquals(listOf("https://host", "https://host:8920"), AddressCandidates.build("https://host"))
    }

    @Test
    fun explicitPortWithoutSchemeIsPreservedForBothProtocols() {
        assertEquals(listOf("https://host:8123", "http://host:8123"), AddressCandidates.build("host:8123"))
    }

    @Test
    fun ipv6LiteralKeepsBrackets() {
        assertEquals(listOf("http://[::1]:8096/jellyfin"), AddressCandidates.build("http://[::1]:8096/jellyfin"))
    }

    @Test
    fun unsafeAndInvalidInputIsRejected() {
        listOf("", "  ", "ftp://host", "http://name:password@host", "host/path?token=secret", "host/#fragment", "bad host", "http://host:invalid", "http://host:99999").forEach {
            assertFailsWith<IllegalArgumentException>(it) { AddressCandidates.build(it) }
        }
    }
}
