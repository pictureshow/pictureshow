/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccessibilityReadingRc3Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun largeText(content: @Composable () -> Unit) {
        compose.setContent {
            PictureshowTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun showOffer() = largeText {
        SupporterScreen(SupporterState.Offer(SupporterProduct("\$2.99", "P1M")), onPurchase = {}, onBack = {})
    }

    private fun offerLines(): List<Pair<String, SemanticsNodeInteraction>> {
        val lines = listOf(
            context.getString(R.string.supporter_title),
            context.getString(R.string.supporter_description),
            context.getString(R.string.supporter_no_guarantee),
            context.getString(R.string.supporter_price_month, "\$2.99"),
            context.getString(R.string.supporter_renewal)
        )
        val buttons = listOf("supporter:purchase", "supporter:back")
        return lines.map { it to compose.onNodeWithText(it) } + buttons.map { it to compose.onNodeWithTag(it) }
    }

    private fun SemanticsNodeInteraction.rect(): Rect = getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    @Test fun supportReadingLinesShareOneHorizontalExtent() {
        showOffer()
        val lines = offerLines().take(5).map { (text, node) -> text to node.rect() }
        val (_, first) = lines.first()
        lines.forEach { (text, rect) ->
            assertEquals(first.left, rect.left, "\"$text\" starts elsewhere: $rect vs $first")
            assertEquals(first.right, rect.right, "\"$text\" ends elsewhere: $rect vs $first")
        }
    }

    /**
     * TalkBack on TV picks the next node by directional search with FocusFinder's beam and weighted-distance rules
     * (`DirectionalTraversalStrategy`). This replays those rules on the laid-out bounds; it is not TalkBack itself.
     */
    @Test fun supportDirectionalReadingVisitsEveryLineBothWays() {
        showOffer()
        val nodes = offerLines().map { (name, node) -> name to node.rect() }
        val names = nodes.map { it.first }
        assertEquals(names.drop(1), walk(nodes, 0, down = true), "DOWN from the heading")
        assertEquals(names.take(5).reversed(), walk(nodes, 5, down = false).take(5), "UP from the purchase button")
    }

    private fun walk(nodes: List<Pair<String, Rect>>, start: Int, down: Boolean): List<String> {
        val visited = mutableListOf<String>()
        var source = nodes[start].second
        while (true) {
            val next = nodes.filter { it.second != source }.fold(null as Pair<String, Rect>?) { best, candidate ->
                if (isBetterCandidate(down, source, candidate.second, best?.second)) candidate else best
            } ?: return visited
            visited += next.first
            source = next.second
        }
    }

    private fun isCandidate(down: Boolean, src: Rect, dest: Rect) = if (down) {
        (src.top < dest.top || src.bottom <= dest.top) && src.bottom < dest.bottom
    } else {
        (src.bottom > dest.bottom || src.top >= dest.bottom) && src.top > dest.top
    }

    private fun inBeam(src: Rect, dest: Rect) = dest.right >= src.left && dest.left <= src.right

    private fun major(down: Boolean, src: Rect, dest: Rect) = max(0f, if (down) dest.top - src.bottom else src.top - dest.bottom)

    private fun majorToFarEdge(down: Boolean, src: Rect, dest: Rect) = max(1f, if (down) dest.bottom - src.bottom else src.top - dest.top)

    private fun beamBeats(down: Boolean, src: Rect, a: Rect, b: Rect): Boolean {
        if (inBeam(src, b) || !inBeam(src, a)) return false
        return major(down, src, a) < majorToFarEdge(down, src, b)
    }

    private fun weighted(down: Boolean, src: Rect, dest: Rect): Float {
        val major = major(down, src, dest)
        val minor = abs(src.center.x - dest.center.x)
        return 13 * major * major + minor * minor
    }

    private fun isBetterCandidate(down: Boolean, src: Rect, candidate: Rect, best: Rect?): Boolean = when {
        !isCandidate(down, src, candidate) -> false
        best == null || !isCandidate(down, src, best) -> true
        beamBeats(down, src, candidate, best) -> true
        beamBeats(down, src, best, candidate) -> false
        else -> weighted(down, src, candidate) < weighted(down, src, best)
    }

    @Test fun aboutTitleIsAHeading() {
        largeText {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "about") { aboutScreens(nav) }
        }
        compose.onNode(hasText("Pictureshow") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    private fun words(text: String) = text.split(Regex("\\s+")).filter(String::isNotEmpty)

    @Test fun licenseChunksNeverCutAWord() {
        val text = (1..60).joinToString(" ") { "redistribution-$it" } + "\n\n" + "short line\n" + "word ".repeat(40)
        val chunks = licenseTextChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(words(text), chunks.flatMap(::words))
    }

    @Test fun licenseReaderKeepsEveryWordWholeAndEveryBlockFitsAtLargeText() {
        lateinit var nav: NavHostController
        largeText {
            nav = rememberNavController()
            NavHost(nav, startDestination = "licenses") { aboutScreens(nav) }
        }
        val catalog = readLicenseCatalog(context)
        val index = catalog.indexOfFirst { it.id == "ffmpeg" }
        compose.runOnIdle { nav.navigate("license/$index") }
        compose.waitForIdle()
        val chunkTag = SemanticsMatcher("license chunk") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("license:chunk:") == true
        }
        val chunks = compose.onAllNodes(chunkTag).fetchSemanticsNodes()
            .sortedBy { it.config[SemanticsProperties.TestTag].removePrefix("license:chunk:").toInt() }
        assertTrue(chunks.size > 1)
        val read = chunks.flatMap { node -> words(node.config[SemanticsProperties.Text].joinToString(" ") { it.text }) }
        assertEquals(words(catalog[index].text), read, "License words must be read whole and in order")
        chunks.forEach { node ->
            // Unclipped: blocks scrolled out of view keep their full height.
            val height = with(compose.density) { node.size.height.toDp().value }
            assertTrue(height <= 488f, "Focused license block is too tall: $height dp")
        }
    }
}
