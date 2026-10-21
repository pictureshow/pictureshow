package app.pictureshow.core.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** Writes each fixture's profile to build/reports/device-profiles so it can be sent to a real server's PlaybackInfo. */
class DeviceProfileReportTest {
    private val pretty = Json(PlaybackJson) { prettyPrint = true }

    @Test
    fun writesEachFixtureProfile() {
        val directory = File("build/reports/device-profiles").apply { mkdirs() }
        for ((name, capabilities) in CapabilityFixtures.all) {
            val profile = DeviceProfileBuilder.build(capabilities)
            val file = File(directory, "$name.json")
            file.writeText(pretty.encodeToString(DeviceProfile.serializer(), profile) + "\n")
            assertEquals(profile, PlaybackJson.decodeFromString(DeviceProfile.serializer(), file.readText()))
        }
        assertEquals(CapabilityFixtures.all.keys.map { "$it.json" }.toSet(), directory.list()!!.toSet())
    }
}
