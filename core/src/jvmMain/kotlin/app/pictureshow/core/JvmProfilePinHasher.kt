/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 with a fresh 128-bit salt and a constant-time comparison. The round count is saved with each
 * hash, so it can rise later without breaking saved PINs. 120,000 rounds keeps a guess under a second on a slow TV;
 * a 4-digit PIN has only 10,000 values anyway, so the guess limit, not the hash, is what protects it in the app.
 */
class JvmProfilePinHasher(private val rounds: Int = 120_000) : ProfilePinHasher {
    override fun create(pin: String): String {
        require(validProfilePin(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2-sha256:$rounds:${salt.hex()}:${derive(pin, salt, rounds).hex()}"
    }

    override fun matches(pin: String, encoded: String): Boolean {
        if (!validProfilePin(pin)) return false
        return runCatching {
            val parts = encoded.split(':')
            require(parts.size == 4 && parts[0] == "pbkdf2-sha256")
            val saved = parts[1].toInt()
            require(saved in 1..10_000_000)
            val salt = parts[2].unhex()
            val expected = parts[3].unhex()
            require(salt.size == 16 && expected.size == 32)
            MessageDigest.isEqual(expected, derive(pin, salt, saved))
        }.getOrDefault(false)
    }

    private fun derive(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, rounds, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun String.unhex(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
