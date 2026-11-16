/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.mpv

import android.content.Context
import android.view.Surface

/**
 * One libmpv event, copied out of mpv's event queue by [MpvNative.waitEvent]. Fields that don't apply to an event
 * keep their defaults: [name] and the values are for property changes, [error] for command replies and end-file,
 * [endReason] for end-file (mpv's `mpv_end_file_reason`), [text] for log messages (`prefix: text`).
 */
class MpvEvent(
    val id: Int,
    val replyId: Long,
    val error: Int,
    val name: String?,
    val format: Int,
    val stringValue: String?,
    val longValue: Long,
    val doubleValue: Double,
    val endReason: Int,
    val text: String?
) {
    companion object {
        // mpv_event_id, from libmpv's client.h (stable API).
        const val NONE = 0
        const val SHUTDOWN = 1
        const val LOG_MESSAGE = 2
        const val COMMAND_REPLY = 5
        const val START_FILE = 6
        const val END_FILE = 7
        const val FILE_LOADED = 8
        const val VIDEO_RECONFIG = 17
        const val AUDIO_RECONFIG = 18
        const val SEEK = 20
        const val PLAYBACK_RESTART = 21
        const val PROPERTY_CHANGE = 22

        // mpv_end_file_reason.
        const val END_EOF = 0
        const val END_STOP = 2
        const val END_QUIT = 3
        const val END_ERROR = 4
        const val END_REDIRECT = 5
    }
}

/**
 * The JNI binding to libmpv (`libpictureshow_mpv.so`, linked dynamically against `libmpv.so`). Every call takes the
 * handle [create] returned. All functions are thread-safe in the sense libmpv's client API is: one thread may block in
 * [waitEvent] while others issue commands; [destroy] must be the last call on a handle and must not race [waitEvent]
 * (call [wakeup] and join the event thread first).
 *
 * Return values follow libmpv: 0 or positive is success, negative is an `mpv_error` code.
 */
object MpvNative {
    // mpv_format.
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5
    const val FORMAT_NODE = 6

    @Volatile private var loaded: Boolean? = null

    /** Loads the native libraries once. False where this ABI has no libmpv build or the libraries fail to load. */
    fun load(): Boolean = loaded ?: synchronized(this) {
        loaded ?: try {
            System.loadLibrary("pictureshow_mpv")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }.also { loaded = it }
    }

    /** mpv_create(), with FFmpeg's Android context set from [context] for MediaCodec. 0 on failure. */
    external fun create(context: Context): Long

    /** mpv_initialize(). Options set before this apply at startup. */
    external fun initialize(handle: Long): Int

    /** mpv_terminate_destroy(). Releases any attached surface reference. */
    external fun destroy(handle: Long)

    external fun setOptionString(handle: Long, name: String, value: String): Int

    /** mpv_command() with a string array, such as `["loadfile", url, "replace"]`. */
    external fun command(handle: Long, args: Array<String>): Int

    /** mpv_command_async(); its result arrives as a COMMAND_REPLY event carrying [replyId]. */
    external fun commandAsync(handle: Long, replyId: Long, args: Array<String>): Int

    /** Null when the property is unavailable. */
    external fun getPropertyString(handle: Long, name: String): String?

    /** NaN when the property is unavailable. */
    external fun getPropertyDouble(handle: Long, name: String): Double

    /** [Long.MIN_VALUE] when the property is unavailable. */
    external fun getPropertyLong(handle: Long, name: String): Long

    /** -1 unavailable, 0 no, 1 yes. */
    external fun getPropertyFlag(handle: Long, name: String): Int

    external fun setPropertyString(handle: Long, name: String, value: String): Int
    external fun setPropertyDouble(handle: Long, name: String, value: Double): Int
    external fun setPropertyLong(handle: Long, name: String, value: Long): Int
    external fun setPropertyFlag(handle: Long, name: String, value: Boolean): Int

    /** mpv_observe_property() with FORMAT_STRING, FORMAT_FLAG, FORMAT_INT64, FORMAT_DOUBLE or FORMAT_NONE. */
    external fun observeProperty(handle: Long, replyId: Long, name: String, format: Int): Int

    /** mpv_request_log_messages(), for example "warn" or "v". */
    external fun requestLogMessages(handle: Long, level: String): Int

    /**
     * Hands mpv a global reference to [surface] through the `wid` option/property, as mpv's Android outputs expect.
     * Replaces (and frees) any earlier reference. Call [detachSurface] before the surface is destroyed.
     */
    external fun attachSurface(handle: Long, surface: Surface): Int

    /** Sets `wid` to 0 (mpv drops its output) and frees the reference. */
    external fun detachSurface(handle: Long): Int

    /** mpv_wait_event(); null for MPV_EVENT_NONE (timeout or [wakeup]). [timeoutSeconds] < 0 waits indefinitely. */
    external fun waitEvent(handle: Long, timeoutSeconds: Double): MpvEvent?

    external fun wakeup(handle: Long)

    /**
     * Build facts for checks and diagnostics, one `key=value` per line: `mpv` (mpv_client_api_version and the mpv
     * version string), `ffmpeg-license` (avcodec_license()), `ffmpeg-configuration` (avcodec_configuration()).
     */
    external fun buildInfo(): String
}
