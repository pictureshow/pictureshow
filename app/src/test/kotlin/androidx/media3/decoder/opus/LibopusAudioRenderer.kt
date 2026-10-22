package androidx.media3.decoder.opus

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.test.utils.FakeRenderer

/** Reflection fixture for testing extension ordering without native extension libraries. */
@UnstableApi
@Suppress("UNUSED_PARAMETER")
class LibopusAudioRenderer(handler: Handler, listener: AudioRendererEventListener, sink: AudioSink) : FakeRenderer(C.TRACK_TYPE_AUDIO)
