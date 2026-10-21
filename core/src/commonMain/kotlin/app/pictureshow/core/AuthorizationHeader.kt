package app.pictureshow.core

/** Jellyfin URL-decodes quoted values; encode delimiter bytes rather than using backslash escapes. */
object AuthorizationHeader {
    fun build(identity: ClientIdentity, token: String? = null): String {
        val values = linkedMapOf(
            "Client" to "Pictureshow",
            "Device" to identity.deviceName,
            "DeviceId" to identity.deviceId,
            "Version" to identity.version
        )
        if (token != null) values["Token"] = token
        return "MediaBrowser " + values.entries.joinToString(", ") { (key, value) -> "$key=\"${encode(value)}\"" }
    }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 255
            if (code in 65..90 || code in 97..122 || code in 48..57 || code.toChar() in "-._~") {
                append(code.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[code ushr 4])
                append("0123456789ABCDEF"[code and 15])
            }
        }
    }
}
