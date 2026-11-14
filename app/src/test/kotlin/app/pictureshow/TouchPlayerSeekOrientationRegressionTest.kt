/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import com.google.common.util.concurrent.Futures
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Production touch player/host and neutral white picture; no future visual flag is passed to a test button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPlayerSeekOrientationRegressionTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private lateinit var output: Media3PlaybackOutput
    private lateinit var decoder: Decoder
    private lateinit var host: PlaybackHostState
    private var density = 0f
    private var contentColor = 0
    private var profileId = "not-shown"
    private var diagnosticIndex = 0
    private var diagnosticTarget: Rect? = null

    private data class Profile(val input: JsonObject, val expected: JsonObject) {
        fun text(key: String) = input.getValue(key).jsonPrimitive.content
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun metric(key: String) = expected.getValue(key).jsonPrimitive.float
        fun expectedText(key: String) = expected.getValue(key).jsonPrimitive.content
    }
    private fun profile(id: String): Profile {
        profileId = id
        val file = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "spec/vectors/player-control-affordances.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("player-control-affordances", suite.getValue("suite").jsonPrimitive.content)
        val c = suite.getValue("cases").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
        return Profile(c.getValue("input").jsonObject, c.getValue("expected").jsonObject)
    }

    @After fun close() {
        compose.runOnIdle {
            if (::host.isInitialized) host.reportScope.cancel()
            if (::decoder.isInitialized) decoder.release()
            if (::output.isInitialized) output.release()
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun show(c: Profile) {
        RuntimeEnvironment.setFontScale(c.number("fontScale"))
        assertEquals("FFFFFFFF", c.text("videoArgb"))
        compose.runOnIdle {
            output = Media3PlaybackOutput(compose.activity, account.server)
            decoder = Decoder(c.number("positionMs").toLong(), c.number("durationMs").toLong())
            host = PlaybackHostState(MediaItem("film", "A film", "Movie"), c.number("positionMs").toLong(), output, {}, {})
            host.playerSource = mutableStateOf(decoder)
            host.stateSource = mutableStateOf(PlaybackState(preparing = false))
            host.duration = c.number("durationMs").toLong()
            assertFalse(host.isLive, "Exercise actual enabled VOD transport wiring")
        }
        compose.setContent {
            TouchTheme {
                val actualContent = LocalContentColor.current.toArgb()
                SideEffect { contentColor = actualContent }
                TouchPlayerContent(account, host, videoContent = { Box(Modifier.fillMaxSize().background(Color.White)) })
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val root = compose.onNodeWithTag("touch:player").fetchSemanticsNode()
        density = root.layoutInfo.density.density
        assertEquals(c.number("densityDpi").toInt(), compose.activity.resources.displayMetrics.densityDpi)
        assertEquals(c.number("densityDpi") / 160f, density, .001f)
        assertEquals(c.number("windowWidthPx"), root.boundsInRoot.width, 1f)
        assertEquals(c.number("windowHeightPx"), root.boundsInRoot.height, 1f)
        val orientation = compose.activity.resources.configuration.orientation
        assertEquals(
            if (c.text("orientation") ==
                "landscape"
            ) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            },
            orientation
        )
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("touch:title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertEquals(c.number("fontScale"), it.layoutInput.density.fontScale, .01f) }
        assertEquals(c.number("positionMs").toLong(), decoder.currentPosition)
    }

    private fun wholeTarget(c: Profile, node: SemanticsNodeInteraction): Rect {
        node.assertIsDisplayed().assertIsEnabled()
        val b = node.fetchSemanticsNode().touchBoundsInRoot
        val frame = compose.onNodeWithTag("touch:player").fetchSemanticsNode().boundsInRoot
        assertTrue(b.width >= c.metric("minimumTargetDp") * density - 1f && b.height >= c.metric("minimumTargetDp") * density - 1f)
        assertTrue(
            b.left >= frame.left && b.top >= frame.top && b.right <= frame.right && b.bottom <= frame.bottom,
            "Whole native target $b in $frame"
        )
        return node.fetchSemanticsNode().boundsInRoot.also { diagnosticTarget = it }
    }

    private data class Palette(val foreground: Int, val backing: Int, val foregroundCorePixels: Int)
    private fun rgb(pixel: Int) = intArrayOf((pixel ushr 16) and 255, (pixel ushr 8) and 255, pixel and 255)
    private fun distance(a: Int, b: Int): Int = rgb(a).zip(rgb(b)).sumOf { (x, y) -> abs(x - y) }
    private fun luminance(pixel: Int): Double {
        val channels = rgb(pixel).map { channel ->
            val s = channel / 255.0
            if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)
        }
        return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722
    }
    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    private fun centered(b: Rect, widthDp: Float, heightDp: Float = widthDp): Rect = Rect(
        b.center.x - widthDp * density / 2f,
        b.center.y - heightDp * density / 2f,
        b.center.x + widthDp * density / 2f,
        b.center.y + heightDp * density / 2f
    )
    private fun points(b: Rect): List<Pair<Int, Int>> = buildList {
        for (y in b.top.roundToInt() until b.bottom.roundToInt()) {
            for (x in b.left.roundToInt() until b.right.roundToInt()) add(x to y)
        }
    }
    private fun pixel(image: Bitmap, root: Rect, x: Int, y: Int): Int {
        val localX = x - root.left.roundToInt()
        val localY = y - root.top.roundToInt()
        assertTrue(localX in 0 until image.width && localY in 0 until image.height)
        return image.getPixel(localX, localY)
    }

    /** Nominal native light-ink core against its adjacent backing, excluding antialias fringe as a contrast requirement. */
    private fun palette(c: Profile, image: Bitmap, root: Rect, region: Rect, exclude: Rect? = null): Palette {
        val samples = points(region).filter { (x, y) ->
            exclude == null ||
                x < exclude.left ||
                x >= exclude.right ||
                y < exclude.top ||
                y >= exclude.bottom
        }
        val candidates = samples.map { (x, y) -> pixel(image, root, x, y) }
            .filter { rgb(it).max() >= c.metric("minimumBrightInkChannel") }
        if (candidates.isEmpty()) recordFailure(image, root, region, "no-bright-core")
        assertTrue(candidates.isNotEmpty(), "Actual native glyph must paint visible light ink")
        val foreground = candidates.groupingBy { it }.eachCount().maxBy { it.value }.key
        val core = samples.filter { (x, y) -> distance(pixel(image, root, x, y), foreground) <= c.metric("coreColorDistance") }
        if (core.size < c.metric("minimumCorePixels")) recordFailure(image, root, region, "too-few-core-pixels")
        assertTrue(core.size >= c.metric("minimumCorePixels"), "Representative actual solid glyph core, not one bright antialias pixel")
        val adjacent = mutableListOf<Int>()
        val radius = c.metric("adjacentRadiusPx").toInt()
        for ((x, y) in core) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx == 0 && dy == 0) continue
                    val p = pixel(image, root, x + dx, y + dy)
                    if (rgb(foreground).max() - rgb(p).max() >= c.metric("minimumBackingChannelDelta")) adjacent += p
                }
            }
        }
        if (adjacent.isEmpty()) recordFailure(image, root, region, "no-adjacent-backing")
        assertTrue(adjacent.isNotEmpty(), "Observe a real adjacent backing around glyph core")
        val backing = adjacent.groupingBy { it }.eachCount().maxBy { it.value }.key
        return Palette(foreground, backing, core.size)
    }

    /** Failure-only native evidence; collector stores images on X8, measurement always uses the original bitmap. */
    private fun recordFailure(image: Bitmap, root: Rect, region: Rect, reason: String) {
        val directory = File(requireNotNull(System.getProperty("user.dir")), "build/reports/player-affordance-diagnostic").apply {
            mkdirs()
        }
        val name = "$profileId-${diagnosticIndex++}-$reason"
        fun rectangle(b: Rect) = org.json.JSONArray(listOf(b.left, b.top, b.right, b.bottom))
        fun crop(b: Rect): Bitmap {
            val left = (b.left - root.left).roundToInt().coerceIn(0, image.width - 1)
            val top = (b.top - root.top).roundToInt().coerceIn(0, image.height - 1)
            val right = (b.right - root.left).roundToInt().coerceIn(left + 1, image.width)
            val bottom = (b.bottom - root.top).roundToInt().coerceIn(top + 1, image.height)
            return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
        }
        val target = diagnosticTarget ?: region
        val targetImage = crop(target)
        val regionImage = crop(region)
        fun statistics(bitmap: Bitmap): org.json.JSONObject {
            val pixels = buildList { for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) add(bitmap.getPixel(x, y)) }
            return org.json.JSONObject()
                .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
                .put("minimumRgbChannels", org.json.JSONArray((0..2).map { channel -> pixels.minOf { rgb(it)[channel] } }))
                .put("maximumRgbChannels", org.json.JSONArray((0..2).map { channel -> pixels.maxOf { rgb(it)[channel] } }))
                .put(
                    "argbModes",
                    org.json.JSONArray(
                        pixels.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12)
                            .map { org.json.JSONObject().put("argb", "%08x".format(it.key)).put("pixels", it.value) }
                    )
                )
        }
        listOf("full" to image, "target" to targetImage, "region" to regionImage).forEach { (suffix, bitmap) ->
            File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(directory, "$name.json").writeText(
            org.json.JSONObject()
                .put("profile", profileId).put("reason", reason).put("density", density)
                .put("localContentArgb", "%08x".format(contentColor)).put("captureBoundsInRoot", rectangle(root))
                .put("targetBoundsInRoot", rectangle(target)).put("sampleBoundsInRoot", rectangle(region))
                .put("targetPixels", statistics(targetImage)).put("samplePixels", statistics(regionImage)).toString(2)
        )
    }

    @Test fun enabledVodSeeksHaveReadableArrowAndTenMarkOverWhiteAtLargeFont() {
        val c = profile("vod-seeks-white-large-font")
        show(c)
        val player = compose.onNodeWithTag("touch:player")
        val root = player.fetchSemanticsNode().boundsInRoot
        val image = player.captureToImage().asAndroidBitmap()
        for (tag in listOf("touch:back10", "touch:forward10")) {
            val b = wholeTarget(c, compose.onNodeWithTag(tag))
            val numeralRegion = centered(b, c.metric("numeralRegionWidthDp"), c.metric("numeralRegionHeightDp"))
            val arrow = palette(c, image, root, centered(b, c.metric("markRegionDp")), exclude = numeralRegion)
            if (contrast(arrow.foreground, arrow.backing) <
                c.metric("minimumArrowContrast")
            ) {
                recordFailure(image, root, centered(b, c.metric("markRegionDp")), "arrow-contrast")
            }
            assertTrue(
                contrast(arrow.foreground, arrow.backing) >= c.metric("minimumArrowContrast"),
                "$tag nominal arrow contrast=${contrast(arrow.foreground, arrow.backing)} palette=$arrow"
            )
            val numeral = palette(c, image, root, numeralRegion)
            if (contrast(numeral.foreground, numeral.backing) <
                c.metric("minimumNumeralContrast")
            ) {
                recordFailure(image, root, numeralRegion, "numeral-contrast")
            }
            assertTrue(
                contrast(numeral.foreground, numeral.backing) >= c.metric("minimumNumeralContrast"),
                "$tag nominal small-10 contrast=${contrast(numeral.foreground, numeral.backing)} palette=$numeral"
            )
            assertTen(c, image, root, b, numeralRegion, numeral)
        }
    }

    /** A visible narrow left stem and enclosed right-hand zero; no future mark tag or implementation's path is consulted. */
    private fun assertTen(c: Profile, image: Bitmap, root: Rect, target: Rect, region: Rect, palette: Palette) {
        val left = region.left.roundToInt()
        val top = region.top.roundToInt()
        val width = region.right.roundToInt() - left
        val height = region.bottom.roundToInt() - top
        fun ink(x: Int, y: Int): Boolean = distance(pixel(image, root, left + x, top + y), palette.foreground) <
            distance(pixel(image, root, left + x, top + y), palette.backing)
        val seen = Array(height) { BooleanArray(width) }
        var zero = false
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (seen[y][x] || ink(x, y)) continue
                val queue = ArrayDeque<Pair<Int, Int>>()
                val component = mutableListOf<Pair<Int, Int>>()
                queue.add(x to y)
                seen[y][x] = true
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    component += at
                    for ((nx, ny) in listOf(
                        at.first - 1 to at.second,
                        at.first + 1 to at.second,
                        at.first to at.second - 1,
                        at.first to at.second + 1
                    )) {
                        if (nx in 0 until width && ny in 0 until height && !seen[ny][nx] && !ink(nx, ny)) {
                            seen[ny][nx] = true
                            queue.add(nx to ny)
                        }
                    }
                }
                val touchesEdge = component.any { it.first == 0 || it.second == 0 || it.first == width - 1 || it.second == height - 1 }
                val minX = component.minOf { it.first }
                val maxX = component.maxOf { it.first }
                val minY = component.minOf { it.second }
                val maxY = component.maxOf { it.second }
                if (!touchesEdge &&
                    component.size >= c.metric("minimumZeroHolePixels") &&
                    left + (minX + maxX) / 2f > target.center.x &&
                    maxX - minX + 1 >= c.metric("minimumZeroHoleWidthDp") * density &&
                    maxY - minY + 1 >= c.metric("minimumZeroHoleHeightDp") * density
                ) {
                    zero = true
                }
            }
        }
        var stem = 0
        for (x in 0 until width) {
            if (left + x >= target.center.x - density) continue
            var run = 0
            for (y in 0 until height) {
                run = if (ink(x, y)) run + 1 else 0
                stem = maxOf(stem, run)
            }
        }
        if (!zero || stem < c.metric("minimumOneStemHeightDp") * density) recordFailure(image, root, region, "ten-ink-shape")
        assertTrue(
            zero && stem >= c.metric("minimumOneStemHeightDp") * density,
            "Actual centered10 ink needs a left stem and closed right zero: zero=$zero stemPx=$stem"
        )
    }

    @Test fun actualLandscapePortraitActionPaintsInwardCorners() = orientation("landscape-portrait-inward-corners")

    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    @Test
    fun actualPortraitLandscapeActionRetainsOutwardCorners() = orientation("portrait-landscape-outward-corners")

    private fun orientation(id: String) {
        val c = profile(id)
        show(c)
        val node = compose.onNodeWithTag("touch:orientation").assertContentDescriptionEquals(c.expectedText("actionDescription"))
        val b = wholeTarget(c, node)
        val player = compose.onNodeWithTag("touch:player")
        val root = player.fetchSemanticsNode().boundsInRoot
        val image = player.captureToImage().asAndroidBitmap()
        val glyph = centered(b, c.metric("glyphViewportDp"))
        val palette = palette(c, image, root, glyph)
        fun count(x0: Float, y0: Float, x1: Float, y1: Float): Int = points(
            Rect(
                glyph.left + x0 * density,
                glyph.top + y0 * density,
                glyph.left + x1 * density,
                glyph.top + y1 * density
            )
        ).count { (x, y) ->
            distance(pixel(image, root, x, y), palette.foreground) < distance(pixel(image, root, x, y), palette.backing)
        }
        val size = c.metric("glyphViewportDp")
        val innerStart = c.metric("innerStartDp")
        val innerEnd = c.metric("innerEndDp")
        val outerStart = c.metric("outerStartDp")
        val outerEnd = c.metric("outerEndDp")
        for (right in listOf(false, true)) {
            for (bottom in listOf(false, true)) {
                fun mirrored(start: Float, end: Float, reverse: Boolean) = if (reverse) size - end to size - start else start to end
                val ix = mirrored(innerStart, innerEnd, right)
                val iy = mirrored(innerStart, innerEnd, bottom)
                val ox = mirrored(outerStart, outerEnd, right)
                val oy = mirrored(outerStart, outerEnd, bottom)
                val inner = count(ix.first, iy.first, ix.second, iy.second)
                val outer = count(ox.first, oy.first, ox.second, oy.second)
                val inward = c.expectedText("cornerDirection") == "inward"
                val desired = if (inward) inner else outer
                val opposite = if (inward) outer else inner
                if (desired < c.metric("minimumDesiredCorePixels") ||
                    opposite > c.metric("maximumOppositeCorePixels")
                ) {
                    recordFailure(image, root, glyph, "corner-direction")
                }
                assertTrue(
                    desired >= c.metric("minimumDesiredCorePixels") && opposite <= c.metric("maximumOppositeCorePixels"),
                    "Actual ${c.expectedText(
                        "actionDescription"
                    )} corner must point ${c.expectedText("cornerDirection")}: right=$right bottom=$bottom inner=$inner outer=$outer"
                )
            }
        }
    }

    private class Decoder(positionMs: Long, durationMs: Long) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var value = State.Builder().setAvailableCommands(
            Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE
            ).build()
        ).setPlaylist(listOf(MediaItemData.Builder("film").setDurationUs(durationMs * 1000).setIsSeekable(true).build()))
            .setPlaybackState(Player.STATE_READY).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(positionMs).build()
        override fun getState(): State = value
        override fun handleSetPlayWhenReady(playWhenReady: Boolean) = Futures.immediateVoidFuture().also {
            value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        override fun handleRelease() = Futures.immediateVoidFuture()
    }
}
