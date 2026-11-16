/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.MediaItem
import app.pictureshow.core.TrailerChoice
import app.pictureshow.core.detailDiscoveryReads
import app.pictureshow.core.extraLabel
import app.pictureshow.core.moreLikeThis
import app.pictureshow.core.runtimeLabel
import app.pictureshow.core.specialFeatureExtras
import app.pictureshow.core.trailerChoices
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The optional rows under a title (CAT-30..32): trailers, extras and More like this. They load beside the detail read and
 * never hold it up; a failed or empty read simply leaves its row out, as other clients do.
 */
data class DetailDiscoveryState(
    val localTrailers: List<MediaItem> = emptyList(),
    val extras: List<MediaItem> = emptyList(),
    val similar: List<MediaItem> = emptyList()
)

class DetailDiscoveryViewModel(private val account: Account, private val item: MediaItem, private val session: CatalogSession) : ViewModel() {
    private val mutableState = MutableStateFlow(DetailDiscoveryState())
    val state = mutableState.asStateFlow()

    init {
        val reads = detailDiscoveryReads(item.type)
        if (reads.any) {
            viewModelScope.launch {
                val trailers = if (reads.trailers) async { session.call(account) { localTrailers(account, item.id) } } else null
                val extras = if (reads.extras) async { session.call(account) { specialFeatures(account, item.id) } } else null
                val similar = if (reads.similar) async { session.call(account) { similar(account, item.id) } } else null
                val trailerItems = (trailers?.await() as? ClientResult.Success)?.value.orEmpty()
                val extraItems = (extras?.await() as? ClientResult.Success)?.value.orEmpty()
                val similarItems = (similar?.await() as? ClientResult.Success)?.value?.items.orEmpty()
                mutableState.update { DetailDiscoveryState(trailerItems, extraItems, similarItems) }
            }
        }
    }
}

/** One card in a discovery row: what it shows, the line under it, and what selecting it does. */
data class DiscoveryEntry(val card: MediaItem, val subtitle: String?, val trailer: TrailerChoice? = null, val play: MediaItem? = null)

data class DiscoveryRow(val key: String, val title: String, val wide: Boolean, val entries: List<DiscoveryEntry>)

internal val DISCOVERY_SECTIONS = setOf("trailers", "extras", "similar")

/** A video with no art of its own borrows the title's backdrop, so a trailer or extra never shows an empty card. */
private fun MediaItem.withTitleArt(title: MediaItem): MediaItem =
    if (imageTags["Thumb"].isNullOrBlank() && imageTags["Primary"].isNullOrBlank() && backdropImageTags.isEmpty()) {
        copy(parentBackdropItemId = title.id, parentBackdropImageTags = title.backdropImageTags)
    } else {
        this
    }

private fun trailerSource(choice: TrailerChoice): String = when {
    choice.item != null -> listOf("Trailer", runtimeLabel(choice.item?.runTimeTicks)).filter(String::isNotBlank).joinToString(" · ")
    choice.youTubeId != null -> "YouTube"
    else -> choice.url?.let { runCatching { Uri.parse(it).host?.removePrefix("www.") }.getOrNull() } ?: "Online"
}

fun discoveryRows(title: MediaItem?, state: DetailDiscoveryState): List<DiscoveryRow> {
    if (title == null) return emptyList()
    val reads = detailDiscoveryReads(title.type)
    return buildList {
        if (reads.trailers) {
            val choices = trailerChoices(state.localTrailers, title.remoteTrailers)
            if (choices.isNotEmpty()) {
                add(
                    DiscoveryRow(
                        "trailers",
                        if (choices.size == 1) "Trailer" else "Trailers",
                        true,
                        choices.map { choice ->
                            val card = choice.item?.withTitleArt(title) ?: MediaItem(
                                id = choice.key,
                                name = choice.title,
                                type = "Trailer",
                                parentBackdropItemId = title.id,
                                parentBackdropImageTags = title.backdropImageTags
                            )
                            DiscoveryEntry(card, trailerSource(choice), trailer = choice, play = choice.item)
                        }
                    )
                )
            }
        }
        val extras = specialFeatureExtras(state.extras)
        if (reads.extras && extras.isNotEmpty()) {
            add(
                DiscoveryRow(
                    "extras",
                    "Extras",
                    true,
                    extras.map { extra ->
                        val line = listOfNotNull(extraLabel(extra.extraType), runtimeLabel(extra.runTimeTicks).takeIf(String::isNotBlank))
                        DiscoveryEntry(extra.withTitleArt(title), line.joinToString(" · "), play = extra)
                    }
                )
            )
        }
        val similar = moreLikeThis(title, state.similar)
        if (reads.similar && similar.isNotEmpty()) {
            add(DiscoveryRow("similar", "More like this", false, similar.map { DiscoveryEntry(it, null) }))
        }
    }
}

/**
 * Opens a remote trailer in another app: YouTube's own app first when the address is a YouTube video (the TV app, then the
 * phone app), then whatever handles the address. Says so when nothing on the device can.
 */
fun openRemoteTrailer(context: Context, choice: TrailerChoice) {
    val url = choice.url ?: return
    val attempts = buildList {
        choice.youTubeId?.let { id ->
            val watch = Uri.parse("https://www.youtube.com/watch?v=$id")
            add(Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube.tv"))
            add(Intent(Intent.ACTION_VIEW, watch).setPackage("com.amazon.firetv.youtube"))
            add(Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube"))
            add(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")))
        }
        add(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    val where = if (choice.youTubeId != null) "YouTube" else "a web browser"
    Toast.makeText(context, "This trailer is online. Install $where on this device to watch it.", Toast.LENGTH_LONG).show()
}

/**
 * What choosing a discovery card does: a local trailer or an extra plays from its start (an extra resumes where the viewer
 * left it), a remote trailer opens in another app, and a More like this title opens its own page.
 */
internal fun openDiscovery(context: Context, entry: DiscoveryEntry, navigate: (String) -> Unit, open: (MediaItem) -> Unit) {
    val play = entry.play
    when {
        play != null -> navigate(playerRoute(play, resume = entry.trailer == null && play.userData.playbackPositionTicks > 0))
        entry.trailer != null -> openRemoteTrailer(context, entry.trailer)
        else -> open(entry.card)
    }
}
