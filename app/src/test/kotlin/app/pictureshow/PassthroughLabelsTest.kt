/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.PassthroughEncoding
import kotlin.test.assertEquals
import org.junit.Test

class PassthroughLabelsTest {
    @Test fun everyAcceptedEncodingHasItsOwnShortLabel() {
        assertEquals(
            "AC3, EAC3, EAC3 JOC, DTS, DTS-HD, TrueHD",
            passthroughEncodingLabels(PassthroughEncoding.entries.toSet())
        )
        PassthroughEncoding.entries.zip(listOf("AC3", "EAC3", "EAC3 JOC", "DTS", "DTS-HD", "TrueHD")).forEach { (encoding, label) ->
            assertEquals(label, passthroughEncodingLabels(setOf(encoding)))
        }
    }

    @Test fun labelOrderDoesNotDependOnRouteEnumerationOrder() {
        assertEquals(
            "AC3, EAC3, EAC3 JOC, DTS, DTS-HD, TrueHD",
            passthroughEncodingLabels(PassthroughEncoding.entries.reversed().toSet())
        )
    }

    @Test fun sharedDiagnosticsUseTheSameDistinctLabels() {
        val capabilities = DeviceCapabilities(
            "Android TV",
            emptyList(),
            DisplayCapabilities(1920, 1080, 60.0),
            emptyList(),
            AudioOutput(2, PassthroughEncoding.entries.toSet())
        )
        val report = thisTvReport(capabilities, "1.0", "11", "HDMI", emptyList())
        assertEquals(
            "Route passthrough encodings: AC3, EAC3, EAC3 JOC, DTS, DTS-HD, TrueHD",
            report.summary.lineSequence().single { it.startsWith("Route passthrough encodings:") }
        )
    }

    @Test fun noEncodedFormatsKeepsPcmOnly() {
        assertEquals("PCM only", passthroughEncodingLabels(emptySet()))
        assertEquals("none reported", passthroughEncodingLabels(emptySet(), emptyLabel = "none reported"))
    }
}
