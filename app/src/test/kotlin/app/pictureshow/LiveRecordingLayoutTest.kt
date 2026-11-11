/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.livetv.LivePolicy
import coil3.asImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-mdpi")
class LiveRecordingLayoutTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://demo.example.test", "12"), User("v", "Viewer"), "token")
    private val recording = MediaItem(
        "r",
        "Generated episode",
        "Episode",
        seriesName = "Generated series",
        runTimeTicks = 1200000000,
        canDownload = true,
        recordingStatus = "Completed",
        overview = "An original generated recording description."
    )

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun squareHorizontalItemsRetainThePlatformScrollPolicy() {
        val platform = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 17f
        }
        val focus = LiveRecordingFocus(platform, 8f, 12f)
        var horizontal: androidx.compose.foundation.gestures.BringIntoViewSpec? = null
        compose.setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().onGloballyPositioned(focus::onViewportPositioned)
            ) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.padding(top = 80.dp).onGloballyPositioned { focus.onShelfPositioned("s", it) }
                ) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.size(20.dp).onSizeChanged { focus.onHeadingSize("s", it.height) }
                    )
                    focus.HorizontalContent {
                        val current = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
                        androidx.compose.runtime.SideEffect { horizontal = current }
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.size(240.dp).onGloballyPositioned { focus.onCardPositioned("r", it) }
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            focus.onCardFocused("s", "r")
            val expected = platform.calculateScrollDistance(0f, 240f, 900f)
            assertTrue(
                focus.verticalSpec.calculateScrollDistance(0f, 240f, 900f) != expected,
                "Square measured items exercise the dimension collision instead of the vertical fallback"
            )
            assertEquals(expected, horizontal!!.calculateScrollDistance(0f, 240f, 900f))
        }
    }

    @Test fun dpadDownRevealsEachRecordingShelfHeadingIncludingTheLastShelf() {
        val fake = LiveFake().apply {
            recordingRows = (1..3).map { index ->
                recording.copy(id = "r$index", seriesName = "Series $index", name = "Episode $index")
            }
        }
        val vm = LiveTvViewModel(account, fake)
        compose.setContent { PictureshowTheme { LiveTvScreen(vm, account, true, {}, {}, {}) } }
        compose.onNodeWithTag("live:tab:Recordings")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("live:r:r1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        (2..3).forEach { index ->
            compose.onNodeWithTag("live:r:r${index - 1}").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(1_000)
            val card = compose.onNodeWithTag("live:r:r$index").assertIsFocused().assertIsDisplayed().getUnclippedBoundsInRoot()
            val heading = compose.onNodeWithTag("live:recording-heading:Series $index", useUnmergedTree = true)
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("live:recording-viewport").getUnclippedBoundsInRoot()
            assertTrue(
                heading.top >= viewport.top + 12.dp && heading.top <= viewport.top + 40.dp,
                "A newly focused shelf puts its heading at the content pivot, including the last shelf: $heading / $viewport"
            )
            assertTrue(card.top >= heading.bottom, "The focused card does not cover its section heading")
            assertTrue(card.bottom <= 513.dp, "The scaled focus edge must fit inside the TV safe area: $card")
        }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test
    fun recordingBackdropUsesTheViewportAndLeavesOrdinaryDetailsAlone() {
        val image = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.RED)
        val loader = coil3.ImageLoader.Builder(compose.activity).components {
            add(coil3.intercept.Interceptor { chain -> coil3.request.SuccessResult(image.asImage(), chain.request) })
        }.build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        try {
            compose.setContent {
                PictureshowTheme {
                    CatalogDetail(
                        CatalogDetailState(account, recording.copy(imageTags = mapOf("Primary" to "generated")), loading = false),
                        {},
                        {},
                        {},
                        {},
                        {},
                        recordingContext = true
                    )
                }
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val viewport = compose.onRoot().getUnclippedBoundsInRoot()
            val backdrop = compose.onNodeWithTag("detail:recording-backdrop", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(viewport.left, backdrop.left)
            assertEquals(viewport.right, backdrop.right)
            assertEquals(viewport.top, backdrop.top, "A recording backdrop belongs to the viewport, not the padded action body")
            assertTrue(kotlin.math.abs((backdrop.bottom - backdrop.top).value - (viewport.bottom - viewport.top).value * .64f) <= 1f)
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val visibleArt = pixels[900, 20]
            assertTrue(
                visibleArt.red > LiveBackground.red + .08f && visibleArt.red > visibleArt.blue + .08f,
                "The loaded red artwork remains visible through the detail body: $visibleArt"
            )
        } finally {
            coil3.SingletonImageLoader.reset()
            loader.shutdown()
            image.recycle()
        }
    }

    @Test fun backdropWithoutAnImageDoesNotPaintTheOrdinaryArtworkPlaceholder() {
        compose.setContent {
            PictureshowTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(LiveBackground)
                ) { RecordingDetailBackdrop(recording, account, androidx.compose.ui.Modifier.fillMaxSize()) }
            }
        }
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        listOf(5 to 5, 120 to 50, 480 to 270, 900 to 500).forEach { (x, y) ->
            assertEquals(LiveBackground, pixels[x, y], "Unavailable backdrop art must not produce a slate panel or ghost title")
        }
    }

    @Test fun tvRecordingCaptionsKeepRelatedLinesTogetherAndPutStatusOnArtwork() {
        val growing = recording.copy(
            episodeTitle = "Actual episode",
            name = "Generated series",
            recordingStatus = "InProgress",
            recordingEndDate = "2026-11-10T23:30:00Z",
            recordingStartDate = "2026-11-10T23:00:00Z"
        )
        compose.setContent {
            PictureshowTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    liveRecordingShelves(
                        LiveTvState(
                            loading = false,
                            policy = LivePolicy(true, true, true, true),
                            recordings = listOf(
                                growing,
                                recording.copy(id = "r2", episodeTitle = "A deliberately longer generated episode title on two lines")
                            )
                        ),
                        account, true, false, null, 0, {}, {}, {}
                    )
                }
            }
        }
        val first = compose.onNodeWithTag("live:r:r").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("live:r:r2").getUnclippedBoundsInRoot()
        assertEquals(first.bottom - first.top, second.bottom - second.top, "Mixed caption lengths retain equal card heights")
        val caption = compose.onNodeWithTag("live:recording:caption:r", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(caption.bottom - caption.top <= 100.dp, "TV has no touch overflow row to reserve")
        val titleNode = compose.onNode(
            androidx.compose.ui.test.hasTestTag("live:recording:title") and hasAnyAncestor(hasTestTag("live:r:r")),
            useUnmergedTree = true
        )
        val metaNode = compose.onNode(
            hasTestTag("live:recording:metadata") and hasAnyAncestor(hasTestTag("live:r:r")),
            useUnmergedTree = true
        )
        val title = titleNode.getUnclippedBoundsInRoot()
        val meta = metaNode.getUnclippedBoundsInRoot()
        // Text semantics expose visible text bounds; use actual layout coordinates for the column's gap.
        val titleCoordinates = titleNode.fetchSemanticsNode().layoutInfo.coordinates
        val metadataCoordinates = metaNode.fetchSemanticsNode().layoutInfo.coordinates
        val density = titleNode.fetchSemanticsNode().layoutInfo.density.density
        val layoutGap = -metadataCoordinates.localPositionOf(
            titleCoordinates,
            androidx.compose.ui.geometry.Offset(0f, titleCoordinates.size.height.toFloat())
        ).y / density
        assertTrue(
            layoutGap.dp in 0.dp..5.dp,
            "Title and metadata retain the 4dp layout gap, including native focus scaling; gap=$layoutGap title=$title metadata=$meta"
        )
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        titleNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        val titleLayout = titleLayouts.single()
        assertEquals(
            1,
            titleLayout.lineCount,
            "The short episode title has one actual text line: ${titleLayout.layoutInput.text} / $title / ${titleLayout.size}"
        )
        val firstLineHeight = titleLayout.getLineBottom(0) - titleLayout.getLineTop(0)
        assertTrue(
            titleLayout.size.height < firstLineHeight * 1.5f,
            "The single-line title must not reserve a second empty line: layout=${titleLayout.size} lineHeight=$firstLineHeight"
        )
        val status = compose.onNodeWithTag("live:recording:status:r", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(status.bottom <= caption.top, "Recording state belongs on the artwork")
        compose.onNodeWithTag("live:r:r").assert(hasContentDescription("Actual episode", substring = true))
    }

    @Test fun recordingDetailKeepsPlayAndDownloadInOneVisibleNativeActionRow() {
        var downloads = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, recording, loading = false),
                    {},
                    {},
                    {},
                    {},
                    {},
                    recordingContext = true,
                    onDownload = { _, _, _ -> downloads++ }
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val play = compose.onNodeWithTag("detail:play").assertIsDisplayed().assertIsFocused().getUnclippedBoundsInRoot()
        val download = compose.onNodeWithTag("detail:download").assertIsDisplayed().getUnclippedBoundsInRoot()
        listOf("played", "favorite", "download").forEach { id ->
            val bounds = compose.onNodeWithTag("detail:$id").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(kotlin.math.abs(bounds.top.value - play.top.value) <= 3, "All actions share the same row: $id $bounds / $play")
            assertTrue(bounds.bottom <= 508.dp && bounds.bottom - bounds.top in 48.dp..53.dp)
        }
        compose.onNodeWithTag("detail:hero", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("detail:title").getUnclippedBoundsInRoot().top >= 32.dp)
        assertTrue(download.right <= 912.dp)
        compose.onNodeWithTag("detail:download").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertEquals(1, downloads)
        assertTrue(liveRecordingItemRoute(recording).endsWith("&liveRecording=true"))
        assertTrue(!itemRoute(recording).contains("liveRecording"), "Ordinary item routes retain their layout default")
    }

    @Test fun ordinaryCatalogDetailRetainsItsExisting220dpHero() {
        compose.setContent {
            PictureshowTheme { CatalogDetail(CatalogDetailState(account, recording, loading = false), {}, {}, {}, {}, {}) }
        }
        val hero = compose.onNodeWithTag("detail:hero", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(220.dp, hero.bottom - hero.top)
    }

    @Test fun recordingCardLongPressAndMenuOpenOptionsWithoutActivatingPlayback() {
        var opened = 0
        var options = 0
        compose.setContent {
            PictureshowTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    liveRecordingShelves(
                        LiveTvState(
                            loading = false,
                            policy = LivePolicy(access = true, deleteContent = true),
                            recordings = listOf(recording)
                        ),
                        account, true, false, null, 0, {}, { opened++ }, { options++ }
                    )
                }
            }
        }
        compose.onNodeWithTag("live:r:r").assertIsFocused().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnLongClick
        ) { assertTrue(it()) }
        compose.onNodeWithTag("live:r:r").performKeyInput { pressKey(Key.Menu) }
        assertEquals(2, options)
        assertEquals(0, opened)
    }
}
