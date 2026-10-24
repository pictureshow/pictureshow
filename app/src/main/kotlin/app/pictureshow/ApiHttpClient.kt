/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.jellyfinApiTransport
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

internal fun createApiHttpClient(): HttpClient = HttpClient(OkHttp) {
    configureApiHttpClient()
    engine { addInterceptor(CancelledResponseInterceptor()) }
}

internal fun HttpClientConfig<*>.configureApiHttpClient() {
    expectSuccess = false
    // A probe never follows a redirect to a different base address or sends auth to it.
    followRedirects = false
    jellyfinApiTransport()
}
