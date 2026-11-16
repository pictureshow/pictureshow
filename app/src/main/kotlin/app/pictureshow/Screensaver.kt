/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.screensaverArt
import coil3.compose.AsyncImage
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.delay

/**
 * SAVER-01: Pictureshow's own screensaver on TV. After a few idle minutes outside the player, the library's backdrops
 * drift by with the time. Any key wakes it without acting on the screen beneath (the activity swallows that press).
 */
internal object TvScreensaver {
    var active by mutableStateOf(false)
}

internal class ScreensaverSettings(context: Context) {
    private val stored = context.applicationContext.getSharedPreferences("screensaver", Context.MODE_PRIVATE)
    var minutes by mutableIntStateOf(stored.getInt("minutes", DEFAULT_MINUTES).takeIf { it in MINUTES } ?: DEFAULT_MINUTES)
        private set
    var maxAge by mutableStateOf(stored.getInt("maxAge", -1).takeIf { it in AGES })
        private set

    fun minutes(value: Int) {
        minutes = value
        stored.edit { putInt("minutes", value) }
    }

    fun maxAge(value: Int?) {
        maxAge = value
        stored.edit { putInt("maxAge", value ?: -1) }
    }

    companion object {
        const val DEFAULT_MINUTES = 5
        val MINUTES = listOf(0, 2, 5, 10, 15, 30)
        val AGES = listOf(7, 10, 13, 16)

        @Volatile private var instance: ScreensaverSettings? = null
        fun of(context: Context): ScreensaverSettings = instance ?: synchronized(this) {
            instance ?: ScreensaverSettings(context).also { instance = it }
        }
    }
}

/** The two rows under Settings: how long before it starts, and which titles' art it may show. */
@Composable
internal fun TvScreensaverSettingsRows() {
    val settings = ScreensaverSettings.of(LocalContext.current)
    TvSettingsSectionHeader("Screensaver")
    TvSettingsChoiceRow(
        "Screensaver",
        settings.minutes,
        ScreensaverSettings.MINUTES,
        { if (it == 0) "Off" else "After $it minutes" },
        Modifier.testTag("settings:screensaver"),
        supportingText = "Shows backdrops from your libraries and the time when nothing has been pressed for a while."
    ) { settings.minutes(it) }
    TvSettingsChoiceRow(
        "Screensaver ratings",
        settings.maxAge,
        listOf<Int?>(null) + ScreensaverSettings.AGES,
        { if (it == null) "Any rating" else "Ages $it and under" },
        Modifier.testTag("settings:screensaver-ratings"),
        enabled = settings.minutes != 0,
        supportingText = "With a limit, only titles rated for those ages appear."
    ) { settings.maxAge(it) }
}

private fun backdropUrl(account: Account, item: MediaItem): String? {
    val tag = item.backdropImageTags.firstOrNull(String::isNotBlank) ?: return null
    return "${account.server.url.trimEnd('/')}/Items/${item.id.encodeURLPathPart()}/Images/Backdrop/0?tag=$tag&maxWidth=1920&quality=85"
}

/**
 * The overlay itself, composed above the TV's navigation. [suppressed] is true in the player, where the picture is the
 * point; the system's own screensaver still applies there when playback is paused.
 */
@Composable
internal fun TvScreensaverHost(account: Account?, session: CatalogSession, suppressed: Boolean) {
    val settings = ScreensaverSettings.of(LocalContext.current)
    val minutes = settings.minutes
    LaunchedEffect(minutes, suppressed, account?.key) {
        TvScreensaver.active = false
        if (minutes == 0 || suppressed || account == null) return@LaunchedEffect
        while (true) {
            val left = minutes * 60_000L - ViewerInput.idleMs()
            if (left <= 0 && !TvScreensaver.active) TvScreensaver.active = true
            delay(if (left > 0) left.coerceAtMost(30_000) else 1_000)
        }
    }
    AnimatedVisibility(TvScreensaver.active && account != null, enter = fadeIn(tween(1200)), exit = fadeOut(tween(300))) {
        if (account != null) ScreensaverContent(account, session, settings.maxAge)
    }
}

@Composable
private fun ScreensaverContent(account: Account, session: CatalogSession, maxAge: Int?) {
    var art by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    LaunchedEffect(account.key, maxAge) {
        val items = (session.call(account) { screensaverItems(account) } as? ClientResult.Success)?.value?.items.orEmpty()
        val ages = if (maxAge == null) {
            emptyMap()
        } else {
            (session.call(account) { parentalRatings(account) } as? ClientResult.Success)?.value.orEmpty()
                .mapNotNull { rating -> rating.value?.let { rating.name to it } }.toMap()
        }
        art = screensaverArt(items, maxAge) { ages[it] }
    }
    LaunchedEffect(art) {
        while (art.size > 1) {
            delay(12_000)
            index = (index + 1) % art.size
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { hideFromAccessibility() }.testTag("screensaver")) {
        Crossfade(art.getOrNull(index), animationSpec = tween(2000), label = "screensaver") { item ->
            if (item != null) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(backdropUrl(account, item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))
                        )
                    )
                    Text(
                        item.name,
                        color = Color(0xFFE6E6E6),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 58.dp, vertical = 40.dp)
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(horizontal = 58.dp, vertical = 32.dp), horizontalAlignment = Alignment.End) {
            Text(
                DateFormat.getTimeFormat(context).format(now),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall
            )
            Text(
                DateFormat.format("EEEE, MMMM d", now).toString(),
                color = Color(0xFFDDDDDD),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
