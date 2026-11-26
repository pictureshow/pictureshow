/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.roundToInt
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
                headers.append(HttpHeaders.Authorization, AuthorizationHeader.build(identity.forAccount(account), account.accessToken))
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
internal fun TrickplayPreview(
    cache: TrickplayCache,
    sourceId: String,
    tile: TrickplayTile,
    positionMs: Long,
    thumbnailHeight: Dp = 135.dp,
    chapterName: String? = null
) {
    val bitmap by produceState<Bitmap?>(null, cache, sourceId, tile) {
        value = null
        value = cache.thumbnail(sourceId, tile)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                null,
                Modifier
                    .size(thumbnailHeight * 16 / 9, thumbnailHeight)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            playerTime(positionMs),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = if (bitmap != null) 6.dp else 0.dp)
        )
        chapterName?.takeIf(String::isNotBlank)?.let { chapter ->
            Text(
                chapter,
                color = Color(0xFFD0D5E2),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Positioned seek preview bubble anchored above the TV seek bar at [fraction]. Shows a trickplay thumbnail when
 * available, and falls back cleanly to a target-time + chapter pill when the item has no trickplay metadata.
 */
@Composable
internal fun TvSeekPreviewBubble(
    fraction: Float,
    positionMs: Long,
    chapterName: String?,
    cache: TrickplayCache?,
    sourceId: String?,
    tile: TrickplayTile?,
    modifier: Modifier = Modifier,
    thumbnailHeight: Dp = 126.dp
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var bubbleWidthPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxWidth().semantics { hideFromAccessibility() }) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val visualFraction = (if (rtl) 1f - fraction else fraction).coerceIn(0f, 1f)
        val anchorPx = (maxWidthPx * visualFraction).roundToInt()
        val maxOffset = (maxWidthPx - bubbleWidthPx).coerceAtLeast(0)
        val offsetX = (anchorPx - bubbleWidthPx / 2).coerceIn(0, maxOffset)
        val hasTrickplay = tile != null && cache != null && sourceId != null
        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX, 0) }
                .onSizeChanged { bubbleWidthPx = it.width }
                .widthIn(min = 84.dp, max = if (hasTrickplay) thumbnailHeight * 16 / 9 + 16.dp else 280.dp)
                .background(Color(0xF2121622), RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(10.dp))
                .padding(horizontal = if (hasTrickplay) 8.dp else 14.dp, vertical = 8.dp)
                .testTag("player:seek-preview"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hasTrickplay) {
                TrickplayPreview(cache, sourceId, tile, positionMs, thumbnailHeight, chapterName)
            } else {
                Text(
                    playerTime(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                chapterName?.takeIf(String::isNotBlank)?.let { chapter ->
                    Text(
                        chapter,
                        color = Color(0xFFD0D5E2),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
