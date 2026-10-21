package app.pictureshow.core

import io.ktor.http.decodeURLQueryComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationHeaderTest {
    @Test
    fun delimiterUnicodePercentAndPlusValuesRoundTripThroughServerParsingRules() {
        val name = "Mom's TV, upstairs \"été\" + 100% \\ screen\r\n"
        val identity = ClientIdentity(name, "id=42,+%\"", "0.1.0+preview")
        val token = "a+b/%=,\""
        val header = AuthorizationHeader.build(identity, token)
        assertTrue(header.startsWith("MediaBrowser "))
        assertTrue(header.all { it.code in 32..126 })
        assertTrue(header.contains("%22"))
        assertTrue(header.contains("%2C"))
        assertTrue(header.contains("%2B"))
        assertEquals(
            mapOf("Client" to "Pictureshow", "Device" to name, "DeviceId" to identity.deviceId, "Version" to identity.version, "Token" to token),
            serverParts(header.substringAfter(' ')),
        )
    }

    @Test
    fun unsignedHeaderOmitsTokenAndKeepsStableClientIdentity() {
        val parts = serverParts(AuthorizationHeader.build(ClientIdentity("TV", "stable-id")).substringAfter(' '))
        assertEquals("Pictureshow", parts["Client"])
        assertEquals("stable-id", parts["DeviceId"])
        assertEquals("0.1.0", parts["Version"])
        assertFalse(parts.containsKey("Token"))
    }

    // Independent behavioral model of the server's quote-aware scan followed by URL decoding.
    // A backslash does not escape a quote in that scan; percent encoding keeps delimiters out.
    private fun serverParts(value: String): Map<String, String> {
        var inQuotes = false
        var start = 0
        var key = ""
        val parts = linkedMapOf<String, String>()
        value.forEachIndexed { index, character ->
            when {
                character == '"' -> inQuotes = !inQuotes
                character == ',' && !inQuotes -> {
                    parts[key] = value.substring(start, index).trim('"').decodeURLQueryComponent(plusIsSpace = true)
                    start = index + 1
                    key = ""
                }
                character == '=' && !inQuotes -> {
                    key = value.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        if (start < value.length) parts[key] = value.substring(start).trim('"').decodeURLQueryComponent(plusIsSpace = true)
        return parts
    }
}
