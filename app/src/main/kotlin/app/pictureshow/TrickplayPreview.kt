/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.TrickplayTile
import app.pictureshow.core.trickplaySheetUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Item-owned, byte-bounded sheet cache. Network and bitmap decoding never run on the player thread. */
internal class TrickplayCache(
    private val http: HttpClient,
    private val account: Account,
    private val identity: ClientIdentity,
    private val itemId: String
) {
    private data class Sheet(val bitmap: Bitmap, val sample: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = Semaphore(2)
    private val pending = mutableMapOf<String, Deferred<Sheet?>>()
    private val sheets = object : LruCache<String, Sheet>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Sheet): Int = value.bitmap.allocationByteCount
    }

    suspend fun thumbnail(sourceId: String, tile: TrickplayTile): Bitmap? = withContext(Dispatchers.IO) {
        val info = tile.info
        val url = trickplaySheetUrl(account.server, itemId, sourceId, info.width, tile.sheet)
        // The item owns downloads, so repeating the remote cancels stale crops, not useful sheet loads.
        val sheet = sheets.get(url) ?: synchronized(pending) {
            pending[url] ?: scope.async(start = CoroutineStart.LAZY) {
                downloads.withPermit { download(url) }
            }.also { deferred ->
                pending[url] = deferred
                deferred.invokeOnCompletion { synchronized(pending) { pending.remove(url, deferred) } }
                deferred.start()
            }
        }.await() ?: return@withContext null
        val width = (info.width / sheet.sample).coerceAtLeast(1)
        val height = (info.height / sheet.sample).coerceAtLeast(1)
        val x = tile.column * info.width / sheet.sample
        val y = tile.row * info.height / sheet.sample
        if (x < 0 || y < 0 || x + width > sheet.bitmap.width || y + height > sheet.bitmap.height) return@withContext null
        Bitmap.createBitmap(sheet.bitmap, x, y, width, height)
    }

    private suspend fun download(url: String): Sheet? = try {
        withTimeout(4_000) {
            val response = http.get(url) {
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity, account.accessToken))
            }
            if (response.status.value != 200) return@withTimeout null
            val bytes = response.body<ByteArray>()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val pixels = options.outWidth.toLong() * options.outHeight
            if (options.outWidth <= 0 || options.outHeight <= 0 || pixels > 100_000_000) return@withTimeout null
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (pixels / (options.inSampleSize * options.inSampleSize) > 8_000_000) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { bitmap ->
                Sheet(bitmap, options.inSampleSize).also { sheets.put(url, it) }
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun clear() = sheets.evictAll()

    fun close() {
        scope.cancel()
        clear()
    }
}

@Composable
internal fun TrickplayPreview(cache: TrickplayCache, sourceId: String, tile: TrickplayTile, positionMs: Long) {
    val bitmap by produceState<Bitmap?>(null, cache, sourceId, tile) {
        value = null
        value = cache.thumbnail(sourceId, tile)
    }
    Column {
        bitmap?.let { Image(it.asImageBitmap(), "Seek preview", Modifier.size(240.dp, 135.dp)) }
        Text(playerTime(positionMs), color = Color.White)
    }
}
