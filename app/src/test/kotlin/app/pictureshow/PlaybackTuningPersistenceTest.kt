/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pictureshow.core.playback.PlaybackTuning
import app.pictureshow.core.playback.SubtitleBurnIn
import app.pictureshow.core.playback.SubtitleStyle
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class PlaybackTuningPersistenceTest {
    @Test fun settingsRoundTripThroughDiskAndKeepExistingChoices() = runTest {
        val directory = Files.createTempDirectory("playback-tuning").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("settings.preferences_pb") }
            val store = PlaybackPreferencesStore(data)
            val tuning = PlaybackTuning(
                remoteBitrateMbps = 10,
                meteredBitrateMbps = 4,
                subtitleBurnIn = SubtitleBurnIn.Always,
                maxAudioChannels = 2,
                rewindSeconds = 15
            )
            val style = SubtitleStyle(foreground = "Cyan", imageOpacity = 40, overrideEmbedded = true)
            store.update { it.copy(bitrateMbps = 40, tuning = tuning, subtitleStyle = style) }
            val saved = store.read()
            assertEquals(tuning, saved.tuning)
            assertEquals(style, saved.subtitleStyle)
            store.update { it.copy(conversionNotice = false) }
            assertEquals(tuning, store.read().tuning)
            assertEquals(40, store.read().bitrateMbps)
            data.edit {
                it[stringPreferencesKey("playback_tuning")] =
                    """{"remoteBitrateMbps":10,"maxAudioChannels":"many","rewindSeconds":15,"subtitleBurnIn":"Future"}"""
                it[stringPreferencesKey("subtitle_style")] = """{"foreground":"Cyan","imageOpacity":"bad","position":"Top"}"""
            }
            assertEquals(10, store.read().tuning.remoteBitrateMbps)
            assertEquals(15, store.read().tuning.rewindSeconds)
            assertEquals(null, store.read().tuning.maxAudioChannels)
            assertEquals(SubtitleBurnIn.Automatic, store.read().tuning.subtitleBurnIn)
            assertEquals("Cyan", store.read().subtitleStyle.foreground)
            assertEquals("Top", store.read().subtitleStyle.position)
            assertEquals(100, store.read().subtitleStyle.imageOpacity)
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }
}
