/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reject unregistered contracts; native presentation contracts name their Android executor explicitly.
 * This inventory validates packaging and envelopes. Each registered runner separately executes its rules.
 */
class CorpusVectorsTest {
    @Test fun everyJsonContractHasAnExecutor() {
        val groups = mapOf(
            "parity-vectors" to
                setOf(
                    "application",
                    "account-sign-out",
                    "catalog",
                    "catalog-return",
                    "discovery",
                    "compatibility",
                    "native-regressions",
                    "negotiation",
                    "network",
                    "playback-information",
                    "playback-formats",
                    "playback",
                    "profiles",
                    "frame-rate-match",
                    "playback-engine",
                    "playback-settings-layout",
                    "playback-settings",
                    "playback-start",
                    "player-quality",
                    "quality-auto-floor",
                    "playback-info-reasons",
                    "settings-presentation",
                    "recovery"
                ),
            "control-vectors" to
                setOf(
                    "cast-capability",
                    "cast-handoff",
                    "cast-session",
                    "cast-queue",
                    "music-catalog",
                    "music-playlists",
                    "music-quality",
                    "music-queue",
                    "music-reports",
                    "music-session",
                    "playback-mode",
                    "queue-edit",
                    "queue-order",
                    "session-command",
                    "session-owner"
                ),
            "offline-vectors" to
                setOf(
                    "offline-budget",
                    "offline-detail-source",
                    "offline-fmp4",
                    "offline-job",
                    "offline-manifest",
                    "offline-next",
                    "offline-outbox",
                    "offline-plan",
                    "offline-reconnect",
                    "offline-resume"
                ),
            "remote-vectors" to setOf("remote-queue", "remote-receive", "remote-reconnect", "remote-targets", "remote-wire"),
            // Core policies run in SyncProtocol/Timing/SourceVectorsTest. Native adapter contracts
            // run in app's SyncPlayHost, RemoteTargetHost, PlaybackSpeed, Controls and SeekFeedback tests.
            "sync-vectors" to
                setOf(
                    "sync-application",
                    "sync-clock",
                    "sync-presentation",
                    // Actual SyncDeparture/LocalDisplay/ChipNameVectorsTest executors.
                    "sync-departure",
                    // SyncStayVectorsTest executes the SYN-08 stay-in-group policy.
                    "sync-stay",
                    "sync-local-display",
                    "sync-chip-name",
                    // Actual Android SyncPlayHostTest scope/identity traces.
                    "sync-route-consent",
                    // Android native render/input tests execute TV and phone surface profiles.
                    "sync-tv-surfaces",
                    // SyncPlayTvRhythmRegressionTest executes native header and setting rhythm.
                    "sync-tv-rhythm",
                    "sync-tv-player-coherence",
                    "sync-tv-attention",
                    "sync-tv-structure-discovery",
                    "sync-group-social-summary-and-picker-role",
                    "sync-phone-surfaces",
                    "sync-phone-player-chrome",
                    "sync-phone-quality-cue",
                    // SyncPhoneRemoteChromeRegressionTest executes native identity, chrome and scroll reachability.
                    "sync-phone-remote-chrome",
                    // RemoteSearchPresentationRegressionTest executes native search layout and deliberate submission.
                    "sync-phone-remote-search",
                    "sync-player-intent-presentation",
                    "sync-speed-intent-origin",
                    "sync-drift",
                    "sync-membership-registration",
                    "sync-queue",
                    "sync-completion",
                    "sync-completion-reporting",
                    "sync-rate-presentation",
                    "sync-socket-readiness",
                    "sync-source",
                    "sync-state"
                ),
            "grid-vectors" to setOf("grid-navigation", "home-browse", "search-presentation"),
            "live-vectors" to setOf("live-guide", "live-dvr", "live-source"),
            "android-native-vectors" to
                setOf(
                    "live-presentation",
                    "player-overlay-layout",
                    // TouchPlayerDirectorRegressionTest and TouchTransportClearanceVectorsTest.
                    "touch-player-director-clearance-paint-edges",
                    // TouchPlayerSeekOrientationRegressionTest and TvPlayerOverflowAffordanceRegressionTest.
                    "player-control-affordances"
                )
        )
        fun directory(name: String) = File(requireNotNull(javaClass.classLoader.getResource(name)) { "Missing packaged $name" }.toURI())
        val inventory = directory("vector-inventory").listFiles()!!.filter {
            it.extension == "json"
        }.associateBy { it.nameWithoutExtension }
        assertEquals(groups.values.flatten().toSet(), inventory.keys, "A new contract needs a registered executor")
        groups.forEach { (resource, names) ->
            assertEquals(
                names,
                directory(resource).listFiles()!!.filter {
                    it.extension == "json"
                }.map { it.nameWithoutExtension }.toSet(),
                resource
            )
            names.forEach { name ->
                val source = inventory.getValue(name).readText()
                assertEquals(source, File(directory(resource), "$name.json").readText(), "Stale resource $name")
                val envelope = Json.parseToJsonElement(source).jsonObject
                assertEquals("1", envelope.getValue("schemaVersion").jsonPrimitive.content, "Schema version $name")
                assertTrue(envelope.getValue("suite").jsonPrimitive.content.isNotBlank(), "Missing suite $name")
                val cases = envelope.getValue("cases").jsonArray
                assertTrue(cases.isNotEmpty(), "Empty contract $name")
                val ids = cases.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                assertEquals(ids.size, ids.toSet().size, "Duplicate ids in $name")
            }
        }
    }
}
