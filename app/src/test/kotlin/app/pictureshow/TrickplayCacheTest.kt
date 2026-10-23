/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.graphics.Bitmap
import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import app.pictureshow.core.TrickplayInfo
import app.pictureshow.core.TrickplayTile
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class TrickplayCacheTest {
    private val account = Account(Server("s", "Test", "http://localhost/base", "12"), User("u", "Test"), "fictional-token")

    @Test
    fun differentFramesReuseAuthenticatedSheetAndClearDropsIt() = runTest {
        val image = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        }.toByteArray()
        var requests = 0
        val http = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("/base/Videos/item/Trickplay/320/0.jpg", request.url.encodedPath)
                assertEquals("source", request.url.parameters["mediaSourceId"])
                assertTrue(request.headers[HttpHeaders.Authorization]!!.contains(account.accessToken))
                respond(image)
            }
        )
        val cache = TrickplayCache(http, account, ClientIdentity("TV", "device"), "item")
        val tile = TrickplayTile(TrickplayInfo(320, 180, 2, 2, 4, 1_000), 0, 0, 0)
        assertEquals(320, assertNotNull(cache.thumbnail("source", tile)).width)
        assertEquals(180, assertNotNull(cache.thumbnail("source", tile.copy(column = 1, row = 1))).height)
        assertEquals(1, requests)
        assertNull(cache.thumbnail("source", tile.copy(column = 2)))
        cache.clear()
        assertNotNull(cache.thumbnail("source", tile))
        assertEquals(2, requests)
        cache.close()
        http.close()
    }

    @Test
    fun cancelledPreviewDoesNotCancelSharedSheetDownload() = runTest {
        val image = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        }.toByteArray()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val http = HttpClient(
            MockEngine {
                requests.incrementAndGet()
                entered.complete(Unit)
                release.await()
                respond(image)
            }
        )
        val cache = TrickplayCache(http, account, ClientIdentity("TV", "device"), "item")
        try {
            val tile = TrickplayTile(TrickplayInfo(320, 180, 2, 2, 4, 1_000), 0, 0, 0)
            val old = async { cache.thumbnail("source", tile) }
            entered.await()
            old.cancelAndJoin()
            val latest = async { cache.thumbnail("source", tile.copy(column = 1)) }
            release.complete(Unit)
            assertNotNull(latest.await())
            assertEquals(1, requests.get())
        } finally {
            cache.close()
            http.close()
        }
    }

    @Test
    fun largeValidSheetIsSubsampledAndCroppedAtTheScaledTileCoordinates() = runTest {
        // 3840x2160 is just over the 8 MP decode limit, so the sheet is sampled by 2. A larger sheet only costs heap:
        // Robolectric keeps pixels on the JVM heap, and a 6400x3600 sheet ran the 512 MB test JVM out mid-suite.
        val image = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        }.toByteArray()
        val http = HttpClient(MockEngine { respond(image) })
        val cache = TrickplayCache(http, account, ClientIdentity("TV", "device"), "item")
        try {
            val tile = TrickplayTile(TrickplayInfo(640, 360, 6, 6, 36, 1_000), 0, 5, 5)
            val thumbnail = assertNotNull(cache.thumbnail("source", tile))
            assertEquals(320, thumbnail.width)
            assertEquals(180, thumbnail.height)
        } finally {
            cache.close()
            http.close()
        }
    }

    @Test
    fun unreadableThumbnailIsOptional() = runTest {
        val http = HttpClient(MockEngine { respond(byteArrayOf(1, 2, 3)) })
        val cache = TrickplayCache(http, account, ClientIdentity("TV", "device"), "item")
        val tile = TrickplayTile(TrickplayInfo(320, 180, 2, 2, 4, 1_000), 0, 0, 0)
        assertNull(cache.thumbnail("source", tile))
        cache.close()
        http.close()
    }
}
