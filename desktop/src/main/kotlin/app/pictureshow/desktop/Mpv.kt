/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import java.io.File

interface MpvApi : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_free(p: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_render_context_create(out: PointerByReference, ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)
}

// Struct arrays are passed as contiguous native memory (including the INVALID terminator).
@Structure.FieldOrder("type", "data")
class RenderParam : Structure() {
    @JvmField var type = 0
    @JvmField var data: Pointer? = null
}
fun renderParams(vararg values: Pair<Int, Pointer>): Array<RenderParam> {
    @Suppress("UNCHECKED_CAST")
    val array = RenderParam().toArray(values.size + 1) as Array<RenderParam>
    values.forEachIndexed { i, (type, ptr) -> array[i].type = type; array[i].data = ptr }
    array.forEach { it.write() }
    return array
}
fun intMemory(value: Int) = Memory(4).apply { setInt(0, value) }
fun cString(value: String) = Memory((value.toByteArray().size + 1).toLong()).apply { setString(0, value) }

private interface NumericLocale : Library { fun setlocale(category: Int, locale: String): String? }

class Mpv(val out: File) : AutoCloseable {
    init {
        // libmpv rejects a JVM process with a non-C numeric locale.
        // LC_NUMERIC is 1 on Linux, 4 on Darwin/Windows; preserve other locale categories.
        val libraries = if (Platform.isWindows()) listOf("msvcrt", "ucrtbase") else listOf(Platform.C_LIBRARY_NAME)
        libraries.forEach { library ->
            Native.load(library, NumericLocale::class.java).setlocale(if (Platform.isLinux()) 1 else 4, "C")
        }
    }
    val api = Native.load(System.getenv("MPV_LIBRARY") ?: System.getProperty("spike.mpv.library") ?: "mpv", MpvApi::class.java)
    val handle = requireNotNull(api.mpv_create()) { "mpv_create failed" }
    init {
        out.mkdirs()
        option("config", "no")
        option("terminal", "yes")
        option("msg-level", "all=warn,vd=info,vo=info")
        option("log-file", File(out, "mpv.log").absolutePath)
        option("hwdec", "auto-safe")
        if (System.getenv("SPIKE_SOFTWARE_GPU") == "1") option("gpu-sw", "yes")
        System.getenv("SPIKE_MPV_OPTIONS")?.split(';')?.filter { it.isNotBlank() }?.forEach { pair -> option(pair.substringBefore('='), pair.substringAfter('=')) }
        option("audio-channels", "auto")
        option("volume", "0") // Never emit sound in an unattended probe.
        if (System.getenv("SPIKE_NULL_AUDIO") == "1") option("ao", "null")
        option("keep-open", "yes")
        option("loop-file", "inf")
        option("input-default-bindings", "no")
        option("input-vo-keyboard", "no")
        // Lua-free LGPL builds have no OSC option or controller.
        val osc = api.mpv_set_option_string(handle, "osc", "no")
        if (osc != -5) check(osc) // MPV_ERROR_OPTION_NOT_FOUND
        option("osd-level", "0")
        option("sub-margin-y", "80")
        option("sub-pos", "85") // Reserve the bottom 15% for the always-visible proof controls.
    }
    fun check(code: Int) { check(code >= 0) { api.mpv_error_string(code) } }
    fun option(name: String, value: String) {
        val rc=api.mpv_set_option_string(handle,name,value)
        check(rc>=0) { "$name=$value: ${api.mpv_error_string(rc)}" }
    }
    fun initialize() = check(api.mpv_initialize(handle))
    fun set(name: String, value: String) = check(api.mpv_set_property_string(handle, name, value))
    fun command(vararg args: String) = check(api.mpv_command(handle, arrayOf(*args, null)))
    fun get(name: String): String? {
        val p = api.mpv_get_property_string(handle, name) ?: return null
        return try { p.getString(0) } finally { api.mpv_free(p) }
    }
    fun dump(stage: String, geometry: String) {
        val keys = listOf("mpv-version", "ffmpeg-version", "mpv-configuration", "current-vo", "hwdec-current",
            "video-codec", "video-format", "video-params/pixelformat", "video-params/primaries",
            "video-params/gamma", "video-out-params/primaries", "video-out-params/gamma",
            "video-params/w", "video-params/h", "audio-codec-name", "audio-params/channels",
            "audio-params/channel-count", "audio-out-params/channels", "audio-out-params/channel-count",
            "audio-device", "sid", "sub-codec", "sub-text", "time-pos", "vo-configured", "eof-reached",
            "estimated-vf-fps", "container-fps", "video-sync", "mistimed-frame-count", "vo-delayed-frame-count", "frame-drop-count", "decoder-frame-drop-count", "display-hidpi-scale")
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val data = keys.associateWith(::get).toMutableMap()
        val count = get("track-list/count")?.toIntOrNull() ?: 0
        repeat(count) { n -> listOf("id", "type", "codec", "selected", "title", "demux-channel-count").forEach { k ->
            data["track-list/$n/$k"] = get("track-list/$n/$k")
        } }
        data["geometry"] = geometry
        File(out, "$stage.json").writeText(data.entries.joinToString(",\n", "{\n", "\n}\n") { (k,v) -> "  ${quote(k)}: ${v?.let(::quote) ?: "null"}" })
    }
    override fun close() = api.mpv_terminate_destroy(handle)
}
