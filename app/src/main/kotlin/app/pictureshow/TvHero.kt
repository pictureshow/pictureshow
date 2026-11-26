/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.BlurHash
import app.pictureshow.core.HeroFacts
import app.pictureshow.core.ImageRef
import app.pictureshow.core.MediaItem
import app.pictureshow.core.blurHashFor
import app.pictureshow.core.heroBackdrop
import app.pictureshow.core.heroFacts
import app.pictureshow.core.heroLogo
import app.pictureshow.core.heroSummary
import app.pictureshow.core.imageRefUrl
import coil3.compose.AsyncImage

/** HOME-23: a BlurHash drawn small and scaled up; null for a malformed hash, so the caller keeps its tint. */
@Composable
internal fun rememberBlurHash(hash: String?, width: Int = 32, height: Int = 18): ImageBitmap? = remember(hash, width, height) {
    hash?.let { BlurHash.decode(it, width, height) }?.let { pixels ->
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

/**
 * A loading tile that is never flat grey: the item's own BlurHash when the server sent one, else a dim tint whose hue comes
 * from the item id, so neighbouring tiles differ and the shelf reads as artwork arriving rather than empty boxes.
 */
@Composable
internal fun ArtPlaceholder(item: MediaItem, ref: ImageRef?, modifier: Modifier = Modifier) {
    val blur = rememberBlurHash(ref?.let { blurHashFor(item, it) })
    Box(modifier.background(placeholderTint(item.id))) {
        if (blur != null) {
            Image(blur, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A dim, slightly coloured gradient seeded by [seed]; dark enough that white text on it stays readable. */
internal fun placeholderTint(seed: String): Brush {
    val hue = ((seed.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    val top = Color.hsl(hue, 0.28f, 0.22f)
    val bottom = Color.hsl((hue + 24f) % 360f, 0.30f, 0.14f)
    return Brush.linearGradient(listOf(top, bottom))
}

/**
 * HOME-20: the full-bleed art behind the focused title. It crossfades when the title changes, draws the BlurHash first,
 * and fades into [background] on its left and bottom so text and shelves in front of it stay readable.
 */
@Composable
internal fun HeroBackdrop(item: MediaItem?, account: Account, background: Color, modifier: Modifier = Modifier) {
    val ref = item?.let(::heroBackdrop)
    val hashes = item?.imageBlurHashes
    // Keyed on the image, not the item: a fresher copy of the same title must not fade its own picture out and in.
    Crossfade(targetState = ref, animationSpec = tween(450), label = "hero-backdrop", modifier = modifier) { shownRef ->
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            if (shownRef != null) {
                val blur = rememberBlurHash(remember(shownRef) { hashes?.get(shownRef.type)?.get(shownRef.tag) })
                if (blur != null) {
                    Image(blur, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(.7f))
                }
                var loaded by remember(shownRef) { mutableStateOf(false) }
                val shown by animateFloatAsState(if (loaded) 1f else 0f, tween(300), label = "backdrop-load")
                AsyncImage(
                    model = imageRefUrl(account.server, shownRef, 1280, quality = 85),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    onSuccess = { loaded = true },
                    modifier = Modifier.fillMaxSize().alpha(shown)
                )
            }
            HeroScrims(background)
        }
    }
}

@Composable
private fun BoxScope.HeroScrims(background: Color) {
    Box(
        Modifier.matchParentSize().background(
            Brush.horizontalGradient(0f to background, .38f to background.copy(alpha = .72f), .7f to Color.Transparent)
        )
    )
    Box(
        Modifier.matchParentSize().background(
            Brush.verticalGradient(.45f to Color.Transparent, .82f to background.copy(alpha = .8f), 1f to background)
        )
    )
}

/** HOME-21: "S1 E1 · 2012 · 24m · TV-Y7 · ★ 6.4", without the end time, which [endsAtLabel] adds in its own style. */
internal fun heroFactsLine(facts: HeroFacts): String = listOfNotNull(
    facts.episodeCode,
    facts.year?.toString(),
    facts.runtime,
    facts.officialRating,
    facts.communityRating?.let(::communityRatingLabel)
).joinToString(" · ")

/** "Ends at 10:45 PM", or 22:45 when the device uses a 24-hour clock. */
@Composable
internal fun endsAtLabel(epochMillis: Long): String {
    val format = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return "Ends at ${format.format(java.util.Date(epochMillis))}"
}

/**
 * The title block a hero shows: the logo when the server has one (the title text stands in until it loads, and stays the
 * spoken name), the facts line with when it would end, and a short summary.
 */
@Composable
internal fun HeroTitleBlock(
    item: MediaItem,
    account: Account,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = System.currentTimeMillis(),
    summaryLines: Int = 3,
    logoHeight: Dp = 72.dp,
    subtitle: String? = null,
    /** One line of smaller title type, for large font scales where every dp of height goes to the shelves. */
    compact: Boolean = false
) {
    val facts = remember(item, nowEpochMillis / 60_000) { heroFacts(item, nowEpochMillis) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroLogoOrTitle(item, account, logoHeight, Modifier.testTag("home:hero:title"), compact = compact)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val line = heroFactsLine(facts)
        if (line.isNotEmpty() || facts.endsAtEpochMillis != null) {
            Text(
                listOfNotNull(line.takeIf(String::isNotEmpty), facts.endsAtEpochMillis?.let { endsAtLabel(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE3E7F2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("home:hero:facts")
            )
        }
        heroSummary(item)?.takeIf { summaryLines > 0 }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFC9CFDD),
                maxLines = summaryLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("home:hero:summary")
            )
        }
    }
}

/** The logo image when the title has one; the name in display type until it loads or when it fails. */
@Composable
internal fun HeroLogoOrTitle(
    item: MediaItem,
    account: Account,
    logoHeight: Dp,
    modifier: Modifier = Modifier,
    title: String = if (item.type == "Episode") item.seriesName ?: item.name else item.name,
    compact: Boolean = false
) {
    val logo = heroLogo(item)
    var logoShown by remember(logo) { mutableStateOf(false) }
    Box(modifier.heightIn(min = if (logo != null) logoHeight else 0.dp), contentAlignment = Alignment.BottomStart) {
        Text(
            title,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(if (logoShown) 0f else 1f).semantics { heading() }
        )
        if (logo != null) {
            AsyncImage(
                model = imageRefUrl(account.server, logo, 800),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onSuccess = { logoShown = true },
                onError = { logoShown = false },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(logoHeight).clearAndSetSemantics {}
            )
        }
    }
}
