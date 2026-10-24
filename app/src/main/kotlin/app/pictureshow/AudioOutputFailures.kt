/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import app.pictureshow.core.playback.PassthroughEncoding

/** An output refusal is evidence about this route, not a permanent device blacklist. */
internal class AudioOutputFailures(
    private val preferences: SharedPreferences,
    private val route: () -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("audio_output_failures", Context.MODE_PRIVATE),
        {
            val manager = context.getSystemService(AudioManager::class.java)
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map {
                    "${it.id}:${it.type}:${if (android.os.Build.VERSION.SDK_INT >= 28) it.address else it.productName}"
                }.sorted().joinToString(";")
            val resolver = context.contentResolver
            devices + "|" + Settings.Global.getString(resolver, "encoded_surround_output") + "|" +
                Settings.Global.getString(resolver, "encoded_surround_output_enabled_formats") + "|" +
                AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null, emptyList()).toString()
        }
    )

    @Synchronized fun rejected(): Set<Int> {
        val time = now()
        if (preferences.getString("route", null) != route() ||
            time < preferences.getLong("at", 0) ||
            time - preferences.getLong("at", 0) >= RETRY_AFTER_MS
        ) {
            preferences.edit { clear() }
            return emptySet()
        }
        return preferences.getStringSet("encodings", emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()
    }

    @Synchronized fun reject(encoding: Int, failedRoute: String): Boolean {
        // A late callback from the previous output must not poison the new one.
        if (failedRoute != route() || encoding !in ENCODINGS.values) return false
        val previous = rejected()
        if (encoding in previous) return false
        preferences.edit {
            putString("route", failedRoute)
            putLong("at", now())
            putStringSet("encodings", (previous + encoding).map(Int::toString).toSet())
        }
        return true
    }

    @Synchronized fun clear() {
        preferences.edit { clear() }
    }

    fun routeKey(): String = route()

    companion object {
        internal const val RETRY_AFTER_MS = 24 * 60 * 60 * 1_000L
        internal val ENCODINGS = mapOf(
            PassthroughEncoding.AC3 to C.ENCODING_AC3,
            PassthroughEncoding.EAC3 to C.ENCODING_E_AC3,
            PassthroughEncoding.EAC3_JOC to C.ENCODING_E_AC3_JOC,
            PassthroughEncoding.DTS to C.ENCODING_DTS,
            PassthroughEncoding.DTS_HD to C.ENCODING_DTS_HD,
            PassthroughEncoding.TRUEHD to C.ENCODING_DOLBY_TRUEHD
        )
    }
}

/** Carries the actual attempted encoding; the sink input MIME can differ (DTS-HD -> DTS core, or decoded PCM). */
internal class EncodedAudioOutputRefused(val encoding: Int, val route: String, cause: Throwable) : Exception(cause)

/** Query and configuration share the same filter. PCM and unrelated working formats retain the live provider. */
internal class RecoverableAudioOutputProvider(delegate: AudioOutputProvider, private val failures: AudioOutputFailures) :
    ForwardingAudioOutputProvider(delegate) {
    private var configuredRoute = failures.routeKey()

    private fun refused(config: AudioOutputProvider.FormatConfig): Boolean {
        val encoding = MimeTypes.getEncoding(config.format.sampleMimeType ?: "", config.format.codecs)
        val rejected = failures.rejected()
        if (encoding in rejected) return true
        if (rejected.isEmpty() || encoding !in AudioOutputFailures.ENCODINGS.values) return false
        // Media3 can map an HD input to DTS core or JOC to E-AC-3. Filter the real output too.
        return try {
            super.getOutputConfig(config).encoding in rejected
        } catch (_: AudioOutputProvider.ConfigurationException) {
            false
        }
    }

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        if (refused(formatConfig)) AudioOutputProvider.FormatSupport.UNSUPPORTED else super.getFormatSupport(formatConfig)

    override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig {
        if (refused(formatConfig)) throw AudioOutputProvider.ConfigurationException("Audio output previously refused this format")
        configuredRoute = failures.routeKey()
        return super.getOutputConfig(formatConfig)
    }

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = try {
        super.getAudioOutput(config)
    } catch (failure: AudioOutputProvider.InitializationException) {
        // Do not learn here: DefaultAudioSink may still succeed with its smaller-buffer retry.
        if (config.encoding in AudioOutputFailures.ENCODINGS.values) {
            throw AudioOutputProvider.InitializationException(EncodedAudioOutputRefused(config.encoding, configuredRoute, failure))
        }
        throw failure
    }
}
