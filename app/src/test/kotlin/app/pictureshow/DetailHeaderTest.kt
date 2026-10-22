package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.resumeLabel
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DetailHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val episode = MediaItem("next", "Next episode", "Episode", userData = ItemUserData(playbackPositionTicks = 900_000_000))
    private val season = MediaItem("season", "Season 1", "Season", seriesId = "series")
    private val series = MediaItem("series", "An Invented Series", "Series", productionYear = 2026, overview = "A short description.")

    private fun show(
        state: CatalogDetailState,
        play: (MediaItem, Boolean) -> Unit = { _, _ ->
        },
        played: () -> Unit = {},
        favorite: () -> Unit = {}
    ) {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent { PictureshowTheme { CatalogDetail(state, {}, {}, played, favorite, {}, play) } }
    }

    private fun key(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    private fun assertHeader(title: String) {
        compose.onNodeWithTag("detail:play").assertIsFocused().assertIsDisplayed()
        val hero = compose.onNodeWithTag("detail:hero").getUnclippedBoundsInRoot()
        assertTrue(hero.top.value >= 0 && hero.bottom.value <= 540, "Hero is offscreen: $hero")
        val bounds = compose.onNode(
            hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        ).getUnclippedBoundsInRoot()
        assertTrue(bounds.top.value >= 0 && bounds.bottom.value <= 540, "Title is offscreen: $bounds")
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test
    fun detailHeroRetainsUpperImageWhenCroppingWideBackdrop() {
        val image = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1600 * 900) { if (it / 1600 < 450) Color.RED else Color.BLUE }
        image.setPixels(pixels, 0, 1600, 0, 0, 1600, 900)
        val loader = ImageLoader.Builder(compose.activity).components {
            add(Interceptor { chain -> SuccessResult(image.asImage(), chain.request) })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            show(CatalogDetailState(account, MediaItem("movie", "A Film", "Movie", backdropImageTags = listOf("fake")), loading = false))
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { compose.activity.window.decorView.draw(Canvas(bitmap)) }
            try {
                // The hero starts at 32dp and is 220dp tall; its lower quarter should still retain
                // the upper half of a 16:9 image, rather than the center crop's lower half.
                assertEquals(Color.RED, bitmap.getPixel(960, (32 + 220 * .75).toInt() * 2))
            } finally {
                bitmap.recycle()
            }
        } finally {
            SingletonImageLoader.reset()
            loader.shutdown()
            image.recycle()
        }
    }

    @Test
    fun seriesOpensAtHeaderPlaysNextUpAtResumeAndUpReturnsFromEveryRow() {
        var playedItem: MediaItem? = null
        var resume = false
        show(
            CatalogDetailState(
                account,
                series,
                loading = false,
                nextUp = listOf(episode),
                seasons = listOf(season),
                selectedSeasonId = season.id,
                episodes = listOf(episode)
            ),
            {
                    item,
                    value
                ->
                playedItem =
                    item
                resume = value
            }
        )
        assertHeader(series.name)
        compose.onNodeWithText(resumeLabel(episode.userData.playbackPositionTicks)).assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(episode.id, playedItem?.id)
            assertTrue(resume)
        }
        key(Key.DirectionDown)
        compose.onNodeWithTag("detail:next:next").assertIsFocused()
        key(Key.DirectionUp)
        assertHeader(series.name)
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        compose.onNodeWithTag("detail:seasons:season").assertIsFocused()
        key(Key.DirectionUp)
        key(Key.DirectionUp)
        assertHeader(series.name)
        repeat(3) { key(Key.DirectionDown) }
        compose.onNodeWithTag("detail:episodes:next").assertIsFocused()
        repeat(3) { key(Key.DirectionUp) }
        assertHeader(series.name)
    }

    @Test
    fun seasonPlaysFirstUnplayedInsteadOfWatchedFirstEpisodeAndExposesParentActions() {
        val watched = episode.copy(id = "watched", userData = ItemUserData(played = true))
        var target: MediaItem? = null
        var resume = false
        var marks = 0
        var favorites = 0
        show(CatalogDetailState(account, season, loading = false, episodes = listOf(watched, episode)), { item, value ->
            target = item
            resume =
                value
        }, { marks++ }, { favorites++ })
        assertHeader(season.name)
        key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(episode.id, target?.id)
            assertTrue(resume)
        }
        key(Key.DirectionRight) // Play from start
        key(Key.DirectionRight) // Mark watched
        key(Key.DirectionCenter)
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(1, marks)
            assertEquals(1, favorites)
        }
        key(Key.DirectionDown)
        compose.onNodeWithTag("detail:episodes:watched").assertIsFocused()
        key(Key.DirectionUp)
        assertHeader(season.name)
    }

    @Test
    fun episodeBackRestoresExactCardAfterReloadTemporarilyRemovesEpisodeShelf() {
        val episodes = (1..3).map { episode.copy(id = "episode-$it", name = "Episode $it") }
        var state by mutableStateOf(
            CatalogDetailState(
                account,
                series,
                loading = false,
                nextUp = listOf(episode),
                seasons = listOf(season),
                selectedSeasonId = season.id,
                episodes = episodes
            )
        )
        var visits = 0
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent {
            PictureshowTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "series") {
                    composable("series") {
                        LaunchedEffect(Unit) {
                            if (visits++ > 0) {
                                state = state.copy(loading = true, episodes = emptyList())
                                delay(300)
                                state = state.copy(loading = false, episodes = episodes)
                            }
                        }
                        CatalogDetail(state, { nav.navigate("episode") }, {}, {}, {}, {})
                    }
                    composable("episode") {
                        CatalogDetail(CatalogDetailState(account, episodes[1], loading = false), {}, {}, {}, {}, {})
                    }
                }
            }
        }
        repeat(3) { key(Key.DirectionDown) }
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:episodes:episode-2").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("detail:episodes:episode-2").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun reloadRestoresFocusedEpisodeAfterTemporarilyRemovingItsShelf() {
        val episodes = (1..3).map { episode.copy(id = "episode-$it", name = "Episode $it") }
        var state by mutableStateOf(
            CatalogDetailState(
                account,
                series,
                loading = false,
                nextUp = listOf(episode),
                seasons = listOf(season),
                selectedSeasonId = season.id,
                episodes = episodes
            )
        )
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent { PictureshowTheme { CatalogDetail(state, {}, {}, {}, {}, {}) } }
        repeat(3) { key(Key.DirectionDown) }
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:episodes:episode-2").assertIsFocused()
        compose.runOnIdle { state = state.copy(loading = true, episodes = emptyList()) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.runOnIdle { state = state.copy(loading = false, episodes = episodes) }
        compose.waitForIdle()
        compose.onNodeWithTag("detail:episodes:episode-2").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun allWatchedSeasonOffersRewatchFromFirstEpisode() {
        var target: String? = null
        var resumed = true
        val watched = episode.copy(userData = ItemUserData(played = true))
        show(CatalogDetailState(account, season, loading = false, episodes = listOf(watched)), { item, resume ->
            target = item.id
            resumed =
                resume
        })
        assertHeader(season.name)
        key(Key.DirectionCenter)
        compose.runOnIdle {
            assertEquals(watched.id, target)
            assertEquals(false, resumed)
        }
    }

    @Test
    fun emptySeriesKeepsHeaderActionsReachableWithoutStartingPlayback() {
        var plays = 0
        show(CatalogDetailState(account, series, loading = false), { _, _ -> plays++ })
        compose.onNodeWithTag("detail:played").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("No episodes are available.").assertExists()
        compose.runOnIdle { assertEquals(0, plays) }
    }
}
