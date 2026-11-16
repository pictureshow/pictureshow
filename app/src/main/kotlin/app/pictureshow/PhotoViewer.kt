/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.SLIDESHOW_SECONDS
import app.pictureshow.core.photoImageUrl
import app.pictureshow.core.photoStep
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal const val PHOTO_ROUTE_PATTERN = "photo?item={item}&sort={sort}&desc={desc}"

/** PHOTO-01: a photo opens the viewer on it, among the photos of its folder in the order the grid showed them. */
internal fun photoRoute(item: MediaItem, sortBy: String = "SortName", descending: Boolean = false) =
    "photo?item=${Uri.encode(Json.encodeToString(item))}&sort=${Uri.encode(sortBy)}&desc=$descending"

data class PhotoViewerState(val photos: List<MediaItem>, val start: Int, val loading: Boolean)

class PhotoViewerViewModel(
    private val account: Account,
    private val item: MediaItem,
    private val sortBy: String,
    private val descending: Boolean,
    private val session: CatalogSession
) : ViewModel() {
    private val mutableState = MutableStateFlow(PhotoViewerState(listOf(item), 0, loading = item.parentId != null))
    val state = mutableState.asStateFlow()

    init {
        val parent = item.parentId
        if (parent != null) {
            viewModelScope.launch {
                // The folder's photos, or just this one when the read fails: the viewer always shows what was chosen.
                val siblings = (session.call(account) { photos(account, parent, sortBy, descending) } as? ClientResult.Success)
                    ?.value?.items.orEmpty()
                val photos = if (siblings.any { it.id == item.id }) siblings else listOf(item) + siblings
                mutableState.value = PhotoViewerState(photos, photos.indexOfFirst { it.id == item.id }.coerceAtLeast(0), loading = false)
            }
        }
    }
}

@Composable
fun PhotoViewer(account: Account, viewModel: PhotoViewerViewModel, tv: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        BackHandler(onBack = onBack)
        return
    }
    val photos = state.photos
    val pager = rememberPagerState(initialPage = state.start) { photos.size }
    val scope = rememberCoroutineScope()
    var slideshow by rememberSaveable { mutableStateOf(false) }
    var seconds by rememberSaveable { mutableIntStateOf(5) }
    // The name and count. On touch the buttons come with them; on TV the buttons are a menu OK opens, so Left and Right
    // always move between photos while it is closed.
    var controls by rememberSaveable { mutableStateOf(true) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val buttons = if (tv) menu else controls
    var touched by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val view = LocalView.current
    val current = photos.getOrNull(pager.currentPage)

    fun step(forward: Boolean) {
        photoStep(pager.currentPage, photos.size, forward, slideshow = false)?.let { scope.launch { pager.animateScrollToPage(it) } }
    }

    // A running slideshow keeps the screen on and moves on by itself; it wraps from the last photo to the first.
    LaunchedEffect(slideshow, seconds, pager.currentPage, touched) {
        view.keepScreenOn = slideshow
        if (slideshow) {
            delay(seconds * 1000L)
            val next = photoStep(pager.currentPage, photos.size, forward = true, slideshow = true)
            if (next == null) slideshow = false else pager.animateScrollToPage(next)
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { pager.currentPage }.collect { touched++ }
    }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }
    // Controls fade after a few seconds so the photo is the whole screen; any key or tap brings them back.
    LaunchedEffect(controls, menu, touched, slideshow) {
        if (controls && !menu) {
            delay(if (slideshow) 2500L else 5000L)
            controls = false
        }
    }
    LaunchedEffect(menu) {
        runCatching { if (menu && tv) firstControl.requestFocus() else focus.requestFocus() }
    }
    BackHandler {
        if (menu) menu = false else onBack()
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val maxWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceIn(640, 3840)
    val maxHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }.coerceIn(360, 2160)

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touched++
                when {
                    !tv || menu -> false
                    event.key == Key.Back -> false
                    event.key == Key.DirectionLeft -> {
                        controls = true
                        step(forward = false)
                        true
                    }
                    event.key == Key.DirectionRight -> {
                        controls = true
                        step(forward = true)
                        true
                    }
                    event.key == Key.MediaPlayPause || event.key == Key.MediaPlay -> {
                        slideshow = !slideshow
                        true
                    }
                    else -> {
                        controls = true
                        menu = true
                        true
                    }
                }
            }
            .focusable()
            .then(
                if (tv) {
                    Modifier
                } else {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        controls = !controls
                    }
                }
            )
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { photos[it].id }) { page ->
            val photo = photos[page]
            AsyncImage(
                model = photoImageUrl(account.server, photo, maxWidth, maxHeight),
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        AnimatedVisibility(controls || buttons, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .then(if (tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier.safeDrawingPadding().padding(16.dp))
            ) {
                Text(current?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    "${pager.currentPage + 1} of ${photos.size}" + if (slideshow) " · Slideshow, every $seconds seconds" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFDDDDDD),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                if (tv && !menu) {
                    Text(
                        "Left and Right change photo · OK for slideshow",
                        color = Color(0xFFBBBBBB),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        AnimatedVisibility(buttons, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .then(if (tv) Modifier.padding(horizontal = 48.dp, vertical = 27.dp) else Modifier.safeDrawingPadding().padding(16.dp)),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!tv) {
                    TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                }
                PhotoControl(
                    if (slideshow) "Pause slideshow" else "Start slideshow",
                    tv,
                    Modifier.focusRequester(firstControl)
                ) {
                    touched++
                    slideshow = !slideshow
                    // On TV the menu gets out of the way of a slideshow that has just started.
                    if (tv && slideshow) menu = false
                }
                PhotoControl(
                    "Every $seconds seconds",
                    tv,
                    Modifier.semantics {
                        contentDescription =
                            "Slideshow speed, every $seconds seconds"
                    }
                ) {
                    touched++
                    seconds = SLIDESHOW_SECONDS[(SLIDESHOW_SECONDS.indexOf(seconds) + 1) % SLIDESHOW_SECONDS.size]
                }
            }
        }
    }
}

@Composable
private fun PhotoControl(label: String, tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (tv) {
        androidx.tv.material3.Button(onClick = onClick, modifier = modifier) { androidx.tv.material3.Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(label, color = Color.White) }
    }
}
