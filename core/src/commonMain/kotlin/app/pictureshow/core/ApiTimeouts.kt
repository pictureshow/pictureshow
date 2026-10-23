/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig

/**
 * Core clients own the whole-call deadline, including response consumption and decoding.
 * Transport owns only connection establishment. Its request/socket timers are disabled so
 * an engine default cannot silently cut a busy server's response short.
 *
 * Jellyfin SDK API calls default to 30 s; its discovery probes use 3.5 s. Keep our existing
 * short automatic port probes: scheme-less input tries up to four candidates, sequentially.
 * A full URL with an explicit port has one candidate and gets the normal API deadline.
 * See docs/network-timeouts.md for sources and the distinction from media streaming.
 */
object ApiTimeouts {
    const val REQUEST_MILLIS = 30_000L
    const val PROBE_MILLIS = 4_000L
    const val CONNECT_MILLIS = 3_000L
}

/** Apply to the HTTP client passed to the core API clients, independently of its engine. */
fun HttpClientConfig<*>.jellyfinApiTransport() {
    install(HttpTimeout) {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        connectTimeoutMillis = ApiTimeouts.CONNECT_MILLIS
    }
}
