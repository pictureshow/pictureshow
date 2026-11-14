/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackSpeeds
import java.io.Closeable
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual source-format callbacks preserve correction rates without emitting viewer Speed requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SyncAudioOutputMaintenanceRegressionTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)

    private fun decision() = PlayDecision(
        "film",
        MediaSourceInfo(runTimeTicks = 9_000_000_000),
        PlayMethod.DirectPlay,
        "https://example.test/Videos/film/stream.ts",
        emptyMap(),
        "session"
    )

    private class Fixture : Closeable {
        val output = Media3PlaybackOutput(
            RuntimeEnvironment.getApplication(),
            Server("s", "Fixture", "https://example.test", "12"),
            upstream = androidx.media3.datasource.DataSource.Factory {
                androidx.media3.datasource.ByteArrayDataSource(byteArrayOf(0))
            }
        )
        val decision = PlayDecision(
            "film",
            MediaSourceInfo(runTimeTicks = 9_000_000_000),
            PlayMethod.DirectPlay,
            "https://example.test/Videos/film/stream.ts",
            emptyMap(),
            "session"
        )
        var grouped = false
        val groupRequests = mutableListOf<String>()
        var source: ExoPlayer
            private set
        var generation: Long
            private set
        init {
            assertEquals(Looper.getMainLooper(), Looper.myLooper(), "Exercise the actual application's player thread")
            output.open(decision, 0, -1, -1, true)
            source = nativePlayer()
            generation = requireNotNull(output.syncNativeSnapshot()).generation
            assertTrue(output.player.value is SyncTransportPlayer, "Use the real exposed transport wrapper")
            output.setSpeed(1.5f)
            assertEquals(1.5f, source.playbackParameters.speed)
            output.syncRequest = { command, _ ->
                if (grouped) groupRequests += command
                grouped
            }
        }

        // Read one already-owned native identity rather than add a test constructor or replace the production player.
        private fun nativePlayer(): ExoPlayer = Media3PlaybackOutput::class.java
            .getDeclaredField("activePlayer").apply { isAccessible = true }.get(output) as ExoPlayer
        fun callback(encoded: Boolean) = output.onSourceAudioOutput(decision, generation, source, encoded)
        fun pcmEvidence() = output.supportsSyncRate(output.player.value)
        fun temporaryRate(rate: Float) {
            output.applySync { output.applySyncRate(rate, requireNotNull(output.player.value)) }
        }
        fun reopen() {
            output.open(decision, 1_000, -1, -1, true)
            source = nativePlayer()
            generation = requireNotNull(output.syncNativeSnapshot()).generation
        }
        override fun close() {
            output.release()
        }
    }

    private fun originCases(): List<JsonObject> {
        val file = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "spec/vectors/sync-speed-intent-origin.json") }.first { it.isFile }
        val suite = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, suite.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("sync-speed-intent-origin", suite.getValue("suite").jsonPrimitive.content)
        return suite.getValue("cases").jsonArray.map { it.jsonObject }.also { assertEquals(4, it.size) }
    }

    private fun originCase(id: String) = Fixture().use { f ->
        val row = originCases().single { it.getValue("id").jsonPrimitive.content == id }
        val input = row.getValue("input").jsonObject
        val expected = row.getValue("expected").jsonObject
        f.grouped = input.getValue("participating").jsonPrimitive.boolean
        assertEquals(input.getValue("initialViewerSpeed").jsonPrimitive.float, f.output.speed.value.speed, id)
        assertFalse(f.pcmEvidence(), "Sink has not yet reported PCM: $id")
        when (input.getValue("origin").jsonPrimitive.content) {
            "viewer" -> f.output.setSpeed(input.getValue("selectedSpeed").jsonPrimitive.float)
            "audio-output-maintenance" -> f.callback(input.getValue("encoded").jsonPrimitive.boolean)
            else -> error("Unsupported origin: $id")
        }
        // Actual adapter calls above supply behavior; this fixture does not reimplement the policy.
        assertEquals(expected.getValue("groupRequests").jsonArray.map { it.jsonPrimitive.content }, f.groupRequests, id)
        assertEquals(expected.getValue("nativeRate").jsonPrimitive.float, f.source.playbackParameters.speed, id)
        assertEquals(expected.getValue("viewerSpeed").jsonPrimitive.float, f.output.speed.value.speed, id)
        assertEquals(expected.getValue("pcmObserved").jsonPrimitive.boolean, f.pcmEvidence(), id)
        assertEquals(1f, f.source.playbackParameters.pitch, id)
    }

    // Separate methods let a negative baseline execute all four JSON cases even if the first one fails.
    @Test fun groupedMaintenanceDoesNotCreateViewerSpeedIntent() = originCase("grouped-audio-output-maintenance")

    @Test fun groupedViewerSelectionStillRequestsSharedSpeedPolicy() = originCase("grouped-viewer")

    @Test fun standaloneMaintenanceKeepsSelectedSpeed() = originCase("standalone-audio-output-maintenance")

    @Test fun standaloneViewerSelectionStillChangesNativeSpeed() = originCase("standalone-viewer")

    private fun assertRejectedCallback(f: Fixture, callback: () -> Unit) {
        f.grouped = true
        f.output.observeSyncAudioOutput(f.generation, false)
        val before = f.output.speed.value
        val nativeBefore = f.source.playbackParameters
        assertTrue(f.pcmEvidence(), "Valid current evidence must be present before an adversarial encoded callback")
        callback()
        assertEquals(before, f.output.speed.value, "Rejected callback must not reset viewer speed, encoded state or explanation")
        assertEquals(nativeBefore, f.source.playbackParameters, "Rejected callback must not touch current native parameters")
        assertTrue(f.pcmEvidence(), "Rejected encoded callback must not erase current PCM evidence")
        assertTrue(f.groupRequests.isEmpty(), "Rejected callback must not manufacture a group command")
    }

    @Test fun staleDecisionIdentityCannotApplyAudioOutput() = Fixture().use { f ->
        val other = decision()
        assertNotSame(f.decision, other, "Equal-looking decisions are still different source lifetimes")
        assertRejectedCallback(f) { f.output.onSourceAudioOutput(other, f.generation, f.source, true) }
    }

    @Test fun staleNativeGenerationCannotApplyAudioOutput() = Fixture().use { f ->
        assertRejectedCallback(f) { f.output.onSourceAudioOutput(f.decision, f.generation + 1, f.source, true) }
    }

    @Test fun wrongNativePlayerIdentityCannotApplyAudioOutput() = Fixture().use { f ->
        Fixture().use { other ->
            assertNotSame(f.source, other.source)
            val otherBefore = other.source.playbackParameters
            assertRejectedCallback(f) { f.output.onSourceAudioOutput(f.decision, f.generation, other.source, true) }
            assertEquals(otherBefore, other.source.playbackParameters, "A foreign native source is not ours to modify")
        }
    }

    @Test fun pcmMaintenanceKeepsActiveTemporaryRateWithoutManufacturingViewerIntent() = Fixture().use { f ->
        f.grouped = true
        f.temporaryRate(1.03f)
        assertEquals(1.03f, f.source.playbackParameters.speed)
        assertTrue(f.groupRequests.isEmpty(), "The actual synchronization bypass already excludes viewer intent")
        f.callback(false)
        assertTrue(f.groupRequests.isEmpty(), "PCM maintenance must not become a viewer Speed request")
        assertEquals(1.03f, f.source.playbackParameters.speed, "Maintenance preserves the existing temporary correction")
        assertEquals(1.5f, f.output.speed.value.speed, "The viewer's selection remains separate from the temporary rate")
        assertEquals(null, f.output.speed.value.message)
        assertTrue(f.pcmEvidence())
    }

    @Test fun encodedMaintenanceForcesNativeOneAndRemovesRateSupportWithoutViewerIntent() = Fixture().use { f ->
        f.grouped = true
        f.temporaryRate(1.03f)
        assertEquals(1.03f, f.source.playbackParameters.speed)
        f.callback(true)
        // Native rate is asserted first: a masked wrapper value cannot prove that encoded output received1×.
        assertEquals(1f, f.source.playbackParameters.speed, "Encoded sink maintenance must reach the owned native decoder")
        assertTrue(f.groupRequests.isEmpty(), "Encoded maintenance is not a viewer speed choice")
        assertEquals(1f, f.output.speed.value.speed)
        assertTrue(f.output.speed.value.encodedAudio)
        assertEquals(PlaybackSpeeds.ENCODED_AUDIO, f.output.speed.value.message)
        assertFalse(f.pcmEvidence())
        assertEquals(1f, f.source.playbackParameters.pitch)
    }

    @Test fun standaloneViewerSpeedSurvivesReplacementAndCurrentMaintenance() = Fixture().use { f ->
        val oldSource = f.source
        val oldGeneration = f.generation
        f.reopen()
        assertNotSame(oldSource, f.source)
        assertTrue(oldGeneration != f.generation)
        f.callback(false)
        assertEquals(1.5f, f.source.playbackParameters.speed, "Current PCM callback restores the selected standalone speed")
        assertEquals(1.5f, f.output.speed.value.speed)
        assertTrue(f.pcmEvidence())
        f.output.setSpeed(2f)
        assertEquals(2f, f.source.playbackParameters.speed, "A genuine standalone viewer selection still reaches native output")
        assertEquals(2f, f.output.speed.value.speed)
        assertTrue(f.groupRequests.isEmpty())
    }
}
