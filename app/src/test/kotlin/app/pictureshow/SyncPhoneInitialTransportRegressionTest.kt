/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.pictureshow.core.sync.SyncQuality
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual compact Dialog placement and one-time transport reveal, including recovery priority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], application=Application::class, qualifiers="w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncPhoneInitialTransportRegressionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun touchMode() {
        shadowOf(compose.activity.getSystemService(UiModeManager::class.java))
            .setCurrentModeType(Configuration.UI_MODE_TYPE_NORMAL)
    }
    @After fun restoreFont() { RuntimeEnvironment.setFontScale(1f) }
    private data class Vector(val input:JsonObject,val expected:JsonObject)
    private fun vector(id:String):Vector {
        val file=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it,"spec/vectors/sync-phone-surfaces.json") }.first { it.isFile }
        val suite=Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1,suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-phone-surfaces",suite.getValue("suite").jsonPrimitive.content)
        val c=suite.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content==id }
        return Vector(c.getValue("input").jsonObject,c.getValue("expected").jsonObject)
    }
    private fun joined(input:JsonObject)=SyncPlayPresentation(
        server="Cinema",viewer="Viewer",policyKnown=true,policy="CreateAndJoinGroups",
        groupId="group",groupName="Friday films",phase=input.getValue("phase").jsonPrimitive.content,
        quality=SyncQuality.UNKNOWN,localPlayback=SyncLocalPlayback.READY,participants=listOf("Morgan","Sam"),
        queue=List(input.getValue("queueEntries").jsonPrimitive.int) { SyncPlayQueueRow("entry-$it", "Film $it", it==0) },
        notice=input.getValue("notice").jsonPrimitive.content.takeUnless { it=="NONE" }?.let { SyncNotice(SyncNoticeKind.valueOf(it),SyncNoticeTone.INFO) }
    )
    private fun show(c:Vector,sent:MutableList<String>) {
        RuntimeEnvironment.setFontScale(c.input.getValue("fontScale").jsonPrimitive.float)
        compose.setContent { app.pictureshow.ui.PictureshowTheme { SyncPlayPanel(joined(c.input),{op,_->sent+=op},{}) } }
        compose.waitForIdle()
        val root=compose.onNodeWithTag("sync:panel").getUnclippedBoundsInRoot()
        // A full-window Dialog can round its logical height by one physical pixel.
        // This setup allowance does not relax any transport/fade geometry assertion below.
        val actualDensity=compose.onNodeWithTag("sync:panel").fetchSemanticsNode().layoutInfo.density.density
        val windowRounding=(c.expected.getValue("windowPhysicalPixelTolerance").jsonPrimitive.float/actualDensity).dp
        assertTrue((root.right-root.left)>=c.expected.getValue("minimumWidthDp").jsonPrimitive.float.dp-windowRounding &&
            (root.bottom-root.top)<=c.expected.getValue("maximumHeightDp").jsonPrimitive.float.dp+windowRounding,"Actual compact native window: $root")
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("sync:phase").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1,layouts.size,"Inspect actual native Text measurement")
        assertEquals(c.input.getValue("fontScale").jsonPrimitive.float,layouts.single().layoutInput.density.fontScale,.01f)
    }
    private fun initialTransport(id:String) {
        val c=vector(id); val sent=mutableListOf<String>();show(c,sent)
        val root=compose.onNodeWithTag("sync:panel").getUnclippedBoundsInRoot()
        val leave=compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        val fade=compose.onAllNodesWithTag("sync:fade-bottom").fetchSemanticsNodes()
        val effectiveBottom=if(fade.isEmpty()) leave.top else
            compose.onNodeWithTag("sync:fade-bottom").getUnclippedBoundsInRoot().top-c.expected.getValue("fadeClearanceDp").jsonPrimitive.float.dp
        for((tag,op) in listOf("sync:previous" to "previous","sync:pause" to "pause","sync:next" to "next")) {
            val node=compose.onNodeWithTag(tag);node.assertIsDisplayed().assertIsEnabled()
            val box=node.getUnclippedBoundsInRoot()
            assertTrue(box.bottom<=effectiveBottom,"Initial $tag=$box must clear actual fade: $effectiveBottom")
            assertTrue(box.top>=root.top && box.left>=root.left && box.right<=root.right,"Entire target in actual window")
            node.performClick();assertEquals(op,sent.last())
        }
        // No scroll or focus request before these checks: first touch arrival is the regression.
    }
    @Test fun initialCompactTransportClearsActualFadeAtFont1()=initialTransport("compact-native-font1")
    @Test fun initialCompactTransportClearsActualFadeAtFont13()=initialTransport("compact-native-font13")
    @Test @Config(qualifiers="w640dp-h280dp-land-xhdpi")
    fun initialShorterWindowTransportClearsFadeWithoutAnySpeedNoticeAtFont1()=initialTransport("compact-shorter-window-no-notice-font1")
    @Test @Config(qualifiers="w640dp-h280dp-land-xhdpi")
    fun initialShorterWindowTransportClearsFadeWithoutAnySpeedNoticeAtFont13()=initialTransport("compact-shorter-window-no-notice-font13")
    @Test fun speedReasonDoesNotPrecedeGroupIdentityAndTransportAsAHero() {
        val c=vector("speed-reason-at-point-of-use");show(c,mutableListOf())
        val reason="Speed is fixed at 1× while watching together."
        val nodes=compose.onAllNodesWithText(reason).fetchSemanticsNodes()
        assertTrue(nodes.size<=c.expected.getValue("maximumCopies").jsonPrimitive.int)
        if(nodes.isNotEmpty()) {
            val target=compose.onNodeWithText(reason)
            val bounds=target.getUnclippedBoundsInRoot()
            val before=listOf("sync:group","sync:now-playing","sync:pause")
                .maxOf { compose.onNodeWithTag(it).getUnclippedBoundsInRoot().bottom }
            assertTrue(bounds.top>=before,"Optional speed explanation follows group/title/transport: $bounds / $before")
            val layouts=mutableListOf<TextLayoutResult>()
            target.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1,layouts.size)
            assertTrue(layouts.single().layoutInput.style.fontSize.value<=c.expected.getValue("maximumReasonTextSp").jsonPrimitive.float,
                "Optional explanation is quiet body text, not a hero")
        }
        // Omitting the panel copy is allowed. Speed context must still explain the constraint in separate coverage.
    }

    @Test @Config(qualifiers="w640dp-h280dp-land-xhdpi")
    fun actionableRecoveryDefersInitialRevealUntilItsActualNoticeClears() {
        val c=vector("compact-initial-reveal-defers-actionable-recovery")
        val sent=mutableListOf<String>()
        val state=showLifecycle(c,sent)
        compose.onNodeWithTag("sync:recovery").assertIsDisplayed().assertIsEnabled()
        assertEquals(c.expected.getValue("recoveryDisplayedBeforeClear").jsonPrimitive.boolean,
            compose.onAllNodesWithTag("sync:recovery").fetchSemanticsNodes().isNotEmpty())
        val tolerance=c.expected.getValue("scrollPhysicalPixelTolerance").jsonPrimitive.float
        assertTrue(kotlin.math.abs(lifecycleScrollValue()-c.expected.getValue("blockedScrollPx").jsonPrimitive.float)<=tolerance,
            "Actionable warning/recovery owns arrival; initial transport reveal must not scroll it away")
        assertTrue(lifecycleScrollMaximum()>0f,"Actual long joined body must be scrollable")
        for(step in c.input.getValue("sequence").jsonArray.map { it.jsonObject }) {
            assertEquals("clear-notice",step.getValue("action").jsonPrimitive.content)
            compose.runOnIdle { state.value=state.value.copy(notice=null,
                phase=step.getValue("phase").jsonPrimitive.content,
                quality=SyncQuality.valueOf(step.getValue("quality").jsonPrimitive.content)) }
            settleLifecycle()
        }
        assertTrue(compose.onAllNodesWithTag("sync:recovery").fetchSemanticsNodes().isEmpty(),
            "Publish must clear the actual recovery composition")
        assertTrue(lifecycleScrollValue()>=c.expected.getValue("minimumRevealScrollPx").jsonPrimitive.float,
            "The now-allowed initial reveal must execute through the real scrolling body")
        assertLifecycleTransportClear(c,"sync:pause")
        assertEquals(c.expected.getValue("transportRequests").jsonPrimitive.int,sent.size,
            "Lifecycle placement must not send group transport")
    }

    @Test @Config(qualifiers="w640dp-h280dp-land-xhdpi")
    fun sameGroupPhaseAndQualityUpdatesDoNotReplayInitialRevealOrStealFocus() {
        val c=vector("compact-initial-reveal-does-not-repeat-for-same-group")
        val sent=mutableListOf<String>()
        val state=showLifecycle(c,sent)
        assertLifecycleTransportClear(c,"sync:pause")
        assertTrue(lifecycleScrollValue()>=c.expected.getValue("minimumRevealScrollPx").jsonPrimitive.float,
            "Fixture must first observe a completed actual initial reveal")
        val group=state.value.groupId
        val focusedBefore=lifecycleFocusedIds()
        val tolerance=c.expected.getValue("scrollPhysicalPixelTolerance").jsonPrimitive.float
        var manuallyPlacedGroup:androidx.compose.ui.unit.DpRect?=null
        for(step in c.input.getValue("sequence").jsonArray.map { it.jsonObject }) {
            when(step.getValue("action").jsonPrimitive.content) {
                "scroll-to-start" -> {
                    val (owner,_)=lifecycleScrollOwner()
                    // Semantics ScrollBy is shared with ancestor nested-scroll consumers.
                    // Reserve one measured window allocation for the expanding app bar too.
                    val panelHeightPx=compose.onNodeWithTag("sync:panel").fetchSemanticsNode().size.height.toFloat()
                    val backToStart=-(lifecycleScrollValue()+panelHeightPx)
                    recordManualScrollFixture("before",owner.id,backToStart)
                    compose.onNode(SemanticsMatcher("actual lifecycle scroll owner") { it.id==owner.id },useUnmergedTree=true)
                        .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f,backToStart) }
                    settleLifecycle()
                    recordManualScrollFixture("after",owner.id,backToStart)
                    assertTrue(kotlin.math.abs(lifecycleScrollValue()-c.expected.getValue("manualScrollPx").jsonPrimitive.float)<=tolerance,
                        "Ordinary actual ScrollBy must return the viewer to the top")
                    manuallyPlacedGroup=compose.onNodeWithTag("sync:group").getUnclippedBoundsInRoot()
                }
                "publish" -> {
                    requireNotNull(manuallyPlacedGroup) { "Profile must manually scroll before republishing" }
                    compose.runOnIdle { state.value=state.value.copy(
                        phase=step.getValue("phase").jsonPrimitive.content,
                        quality=SyncQuality.valueOf(step.getValue("quality").jsonPrimitive.content)) }
                    settleLifecycle()
                    assertEquals(group,state.value.groupId,"Publish keeps actual membership identity")
                    assertTrue(kotlin.math.abs(lifecycleScrollValue()-c.expected.getValue("scrollPxAfterPublish").jsonPrimitive.float)<=tolerance,
                        "Same-group phase/quality publication must not replay initial reveal")
                    val after=compose.onNodeWithTag("sync:group").getUnclippedBoundsInRoot()
                    val density=compose.onNodeWithTag("sync:group").fetchSemanticsNode().layoutInfo.density.density
                    assertTrue(kotlin.math.abs((after.top-requireNotNull(manuallyPlacedGroup).top).value*density)<=tolerance,
                        "Actual group anchor must stay where the viewer manually placed it")
                    assertEquals(c.expected.getValue("unchangedFocusedNodeIds").jsonPrimitive.boolean,
                        focusedBefore==lifecycleFocusedIds(),"Placement must not steal or move focus")
                }
                else -> error("Unsupported initial-reveal lifecycle input")
            }
        }
        assertEquals(c.expected.getValue("transportRequests").jsonPrimitive.int,sent.size)
    }

    private fun showLifecycle(c:Vector,sent:MutableList<String>):MutableState<SyncPlayPresentation> {
        RuntimeEnvironment.setFontScale(c.input.getValue("fontScale").jsonPrimitive.float)
        val initialNotice=c.input.getValue("initialNotice").takeUnless { it is JsonNull }?.jsonObject?.let {
            SyncNotice(SyncNoticeKind.valueOf(it.getValue("kind").jsonPrimitive.content),
                SyncNoticeTone.valueOf(it.getValue("tone").jsonPrimitive.content),
                SyncNoticeAction.valueOf(it.getValue("action").jsonPrimitive.content))
        }
        val state=mutableStateOf(joined(c.input).copy(notice=initialNotice))
        compose.setContent { app.pictureshow.ui.PictureshowTheme { SyncPlayPanel(state.value,{op,_->sent+=op},{}) } }
        settleLifecycle()
        val panel=compose.onNodeWithTag("sync:panel")
        val box=panel.getUnclippedBoundsInRoot()
        val density=panel.fetchSemanticsNode().layoutInfo.density.density
        val rounding=(c.expected.getValue("windowPhysicalPixelTolerance").jsonPrimitive.float/density).dp
        assertTrue(box.right-box.left>=c.expected.getValue("minimumWidthDp").jsonPrimitive.float.dp-rounding &&
            box.bottom-box.top<=c.expected.getValue("maximumHeightDp").jsonPrimitive.float.dp+rounding,
            "Actual native lifecycle fixture must use the claimed compact window: $box")
        val layouts=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("sync:phase").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1,layouts.size)
        assertEquals(c.input.getValue("fontScale").jsonPrimitive.float,layouts.single().layoutInput.density.fontScale,.01f)
        return state
    }
    private fun settleLifecycle() {
        compose.waitForIdle();compose.mainClock.advanceTimeBy(1_000);compose.waitForIdle()
    }
    private fun lifecycleScrollOwner():Pair<androidx.compose.ui.semantics.SemanticsNode,androidx.compose.ui.semantics.ScrollAxisRange> {
        val target=compose.onNodeWithTag("sync:previous",useUnmergedTree=true).fetchSemanticsNode()
        val owner=generateSequence(target.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        } ?: error("Actual transport must have a native vertical scroll owner")
        return owner to owner.config[SemanticsProperties.VerticalScrollAxisRange]
    }
    private fun lifecycleScrollValue()=lifecycleScrollOwner().second.value()
    private fun lifecycleScrollMaximum()=lifecycleScrollOwner().second.maxValue()
    private fun lifecycleFocusedIds()=compose.onAllNodes(SemanticsMatcher("actual focused nodes") {
        it.config.getOrNull(SemanticsProperties.Focused)==true
    },useUnmergedTree=true).fetchSemanticsNodes().map { it.id }.sorted()
    private fun assertLifecycleTransportClear(c:Vector,primary:String) {
        val leave=compose.onNodeWithTag("sync:leave").getUnclippedBoundsInRoot()
        val fade=compose.onAllNodesWithTag("sync:fade-bottom").fetchSemanticsNodes()
        val effectiveBottom=if(fade.isEmpty()) leave.top else
            compose.onNodeWithTag("sync:fade-bottom").getUnclippedBoundsInRoot().top-c.expected.getValue("fadeClearanceDp").jsonPrimitive.float.dp
        for(tag in listOf("sync:previous",primary,"sync:next")) {
            val target=compose.onNodeWithTag(tag);target.assertIsDisplayed().assertIsEnabled()
            val box=target.getUnclippedBoundsInRoot()
            assertTrue(box.bottom<=effectiveBottom,"Actual entire transport clears live20dp fade+8dp: $tag=$box bottom=$effectiveBottom")
        }
    }

    /** Observation only: leave evidence even if the following strict manual-zero prerequisite fails. */
    private fun recordManualScrollFixture(stage:String,ownerId:Int,requestedDeltaPx:Float) {
        fun rect(box:androidx.compose.ui.geometry.Rect)=org.json.JSONArray(listOf(box.left,box.top,box.right,box.bottom))
        val bounds=org.json.JSONArray()
        for(tag in listOf("sync:panel","sync:close","sync:group","sync:phase","sync:previous","sync:next","sync:fade-top","sync:fade-bottom")) {
            val matches=compose.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes()
            matches.forEach { node -> bounds.put(org.json.JSONObject().put("tag",tag).put("id",node.id)
                .put("boundsInRootPxClipped",rect(node.boundsInRoot))
                .put("positionInRootPx",org.json.JSONArray(listOf(node.positionInRoot.x,node.positionInRoot.y)))
                .put("sizePx",org.json.JSONArray(listOf(node.size.width,node.size.height)))) }
        }
        val owner=lifecycleScrollOwner().first
        val ancestors=org.json.JSONArray()
        generateSequence(owner) { it.parent }.forEach { node ->
            ancestors.put(org.json.JSONObject().put("id",node.id)
                .put("tag",node.config.getOrNull(SemanticsProperties.TestTag)?:"")
                .put("boundsInRootPxClipped",rect(node.boundsInRoot))
                .put("sizePx",org.json.JSONArray(listOf(node.size.width,node.size.height))))
        }
        val data=org.json.JSONObject().put("stage",stage).put("ownerId",ownerId)
            .put("requestedDeltaPx",requestedDeltaPx).put("actualInnerScrollPx",lifecycleScrollValue())
            .put("actualInnerMaximumPx",lifecycleScrollMaximum())
            .put("focusedNodeIds",org.json.JSONArray(lifecycleFocusedIds()))
            .put("actualTaggedGeometry",bounds).put("scrollOwnerAncestors",ancestors)
        val directory=File("build/reports/initial-reveal-manual-scroll")
        check(directory.mkdirs()||directory.isDirectory)
        File(directory,"$stage.json").writeText(data.toString(2)+"\n")
        println("INITIAL_REVEAL_MANUAL_SCROLL "+data.toString())
    }
}
