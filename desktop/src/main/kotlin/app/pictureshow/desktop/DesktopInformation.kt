/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.playback.DeviceProfile
import java.awt.GraphicsEnvironment
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

const val PICTURESHOW_SOURCE_URL = "https://github.com/pictureshow/pictureshow"
const val PICTURESHOW_PRIVACY_URL = "https://pictureshow.app/privacy/"

/** One row of the generated `licenses/catalog.json` (desktop/licenses.gradle): the same shape as Android's. */
@Serializable
data class DesktopLicense(val id: String, val name: String, val version: String, val license: String, val text: String, val notices: List<String> = emptyList())

@Serializable
private data class DesktopLicenseCatalog(val libraries: List<DesktopLicense>)

/** INFO-04: the catalog generated from the real runtime classpath at build time; null when the resource is missing. */
internal fun desktopLicenseCatalog(loader: ClassLoader = DesktopLicense::class.java.classLoader): List<DesktopLicense>? =
    loader.getResourceAsStream("licenses/catalog.json")?.use { stream ->
        val libraries = Json { ignoreUnknownKeys = true }.decodeFromString<DesktopLicenseCatalog>(stream.readBytes().toString(Charsets.UTF_8)).libraries
        val sources = desktopPlaybackSources() ?: return libraries
        val lgpl = File(sources, "COPYING.LGPLv2.1").readText()
        val inventory = File(sources, "LICENSE-INVENTORY.md").readText()
        val included = DesktopLicense(
            "mac-playback", "mpv (libmpv) and FFmpeg", "mpv 0.42.0 · FFmpeg 8.0", "LGPL-2.1-or-later",
            "This copy of Pictureshow includes mpv and FFmpeg built without their GPL or nonfree parts. " +
                "The source archives, changes and third-party notices are included in Playback Sources.\n\n$inventory\n\n$lgpl"
        )
        (libraries.filterNot { it.id == "mpv-system" || it.id.startsWith("org.jogamp.") } + included).sortedBy { it.name.lowercase() }
    }

internal fun desktopPlaybackSources(): File? = System.getProperty("pictureshow.playback.sources")
    ?.let(::File)?.takeIf { it.isDirectory }

internal fun desktopOwnLicense(loader: ClassLoader = DesktopLicense::class.java.classLoader): String? =
    loader.getResourceAsStream("licenses/MPL-2.0.txt")?.use { it.readBytes().toString(Charsets.UTF_8) }

data class DesktopDisplay(val width: Int, val height: Int, val refreshHz: Int?, val bitDepth: Int?, val scale: Double?)

data class DesktopDecoder(val codec: String, val description: String, val video: Boolean)

/** INFO-01/02: what this computer reported, each line measured; nothing here is assumed from the platform. */
data class DesktopComputerReport(
    val os: String,
    val java: String,
    val mpvVersion: String?,
    val ffmpegVersion: String?,
    /** True only when mpv's own build options say `gpl=false`; null when it didn't say. */
    val mpvLgpl: Boolean?,
    val hwdec: String?,
    val videoDecoders: List<DesktopDecoder>,
    val audioDecoders: List<DesktopDecoder>,
    val displays: List<DesktopDisplay>,
    val profileJson: String?,
    val mpvError: String?
)

data class DesktopInformationState(
    val licenses: List<DesktopLicense>? = null,
    val licensesMissing: Boolean = false,
    val license: DesktopLicense? = null,
    val report: DesktopComputerReport? = null,
    val reportLoading: Boolean = false
)

/** The mpv probe for the report: the loaded libmpv's own answers. Behind a seam so tests needn't load native code. */
fun interface DesktopMpvProbe { fun read(keys: List<String>): Map<String, String?> }

/** [directory] is the app's own data directory, never a shared temp path another user could pre-create. */
internal fun nativeMpvProbe(directory: File) = DesktopMpvProbe { keys ->
    val mpv = Mpv(directory, probe = true)
    try {
        mpv.option("vo", "null"); mpv.option("ao", "null"); mpv.option("terminal", "no")
        mpv.initialize()
        keys.associateWith { runCatching { mpv.get(it) }.getOrNull() }
    } finally { mpv.close() }
}

/** Video codecs among FFmpeg's decoder names, for splitting decoder-list into video and audio. */
private val VIDEO_HINTS = setOf("h264", "hevc", "av1", "vp8", "vp9", "mpeg1video", "mpeg2video", "mpeg4", "vc1", "wmv1", "wmv2", "wmv3",
    "prores", "ffv1", "dnxhd", "theora", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "h263", "mjpeg", "rv10", "rv20", "rv30", "rv40",
    "cinepak", "huffyuv", "rawvideo", "vvc", "flv1", "indeo3", "indeo5", "svq1", "svq3", "utvideo", "cfhd", "dirac")
private val AUDIO_HINTS = Regex("^(aac|ac3|eac3|truehd|mlp|dts|dca|flac|alac|opus|vorbis|mp1|mp2|mp3|pcm_.*|wma.*|amr.*|ape|tak|tta|wavpack|als|atrac.*|cook|ra_.*|gsm.*|speex|adpcm_.*|dsd_.*|qdm2|nellymoser|g72.*|mpc.*|sipr|twinvq|on2avc|dolby_e|s302m)$")

internal fun desktopSplitDecoders(decoderList: String?): Pair<List<DesktopDecoder>, List<DesktopDecoder>> {
    val entries = runCatching { Json.parseToJsonElement(decoderList ?: return emptyList<DesktopDecoder>() to emptyList()).jsonArray }.getOrNull()
        ?: return emptyList<DesktopDecoder>() to emptyList()
    val decoders = entries.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val codec = o["codec"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        when {
            codec in VIDEO_HINTS -> DesktopDecoder(codec, description, video = true)
            AUDIO_HINTS.matches(codec) -> DesktopDecoder(codec, description, video = false)
            else -> null
        }
    }.distinctBy { it.codec }.sortedBy { it.codec }
    return decoders.filter { it.video } to decoders.filter { !it.video }
}

/** mpv reports its meson options in `mpv-configuration`; only an explicit gpl=false counts as LGPL. */
internal fun desktopMpvLgpl(configuration: String?): Boolean? = when {
    configuration == null -> null
    Regex("""-?Dgpl=false|\bgpl=false""").containsMatchIn(configuration) -> true
    Regex("""-?Dgpl=true|\bgpl=true""").containsMatchIn(configuration) -> false
    else -> null
}

internal fun desktopDisplays(): List<DesktopDisplay> = runCatching {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val mode = device.displayMode
        val transform = runCatching { device.defaultConfiguration.defaultTransform.scaleX }.getOrNull()
        DesktopDisplay(mode.width, mode.height, mode.refreshRate.takeIf { it > 0 }, mode.bitDepth.takeIf { it > 0 }, transform)
    }
}.getOrDefault(emptyList())

/** The plain answer that opens the report: what most files will do here. */
internal fun desktopComputerVerdict(report: DesktopComputerReport): String = when {
    report.mpvVersion == null -> "Pictureshow can't play video on this computer yet: it couldn't load libmpv. Install mpv, or set MPV_LIBRARY to a libmpv file, then reopen Pictureshow."
    else -> {
        val v = report.videoDecoders.map { it.codec }.toSet()
        val named = listOf("h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1", "vp9" to "VP9").filter { it.first in v }.map { it.second }
        "This computer plays ${named.joinToString(", ").ifEmpty { "few common formats" }} and ${report.audioDecoders.size} audio formats without the server converting them."
    }
}

class DesktopInformation(
    private val scope: CoroutineScope,
    private val settings: () -> DesktopSettings,
    private val probe: DesktopMpvProbe,
    private val catalog: () -> List<DesktopLicense>? = { desktopLicenseCatalog() }
) {
    private val mutable = MutableStateFlow(DesktopInformationState())
    val state: StateFlow<DesktopInformationState> = mutable.asStateFlow()

    fun loadLicenses() {
        if (mutable.value.licenses != null) return
        scope.launch(Dispatchers.IO) {
            val rows = runCatching { catalog() }.getOrNull()
            mutable.update { it.copy(licenses = rows ?: emptyList(), licensesMissing = rows == null) }
        }
    }

    fun openLicense(id: String) { mutable.update { s -> s.copy(license = s.licenses?.firstOrNull { it.id == id }) } }

    /** Probes on every visit: the libmpv on this computer can change between visits (MPV_LIBRARY, an update). */
    fun loadReport() {
        if (mutable.value.reportLoading) return
        mutable.update { it.copy(reportLoading = true) }
        scope.launch(Dispatchers.IO) {
            val answers = try { Result.success(probe.read(listOf("mpv-version", "ffmpeg-version", "mpv-configuration", "decoder-list", "hwdec"))) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) { Result.failure(failure) }
            val values = answers.getOrNull().orEmpty()
            val (video, audio) = desktopSplitDecoders(values["decoder-list"])
            val codecs = (video + audio).map { it.codec }.toSet()
            val profile: DeviceProfile? = if (codecs.isNotEmpty()) runCatching { desktopMpvProfile(settings(), codecs) }.getOrNull() else null
            val report = DesktopComputerReport(
                os = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
                java = "${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
                mpvVersion = values["mpv-version"], ffmpegVersion = values["ffmpeg-version"],
                mpvLgpl = desktopMpvLgpl(values["mpv-configuration"]), hwdec = values["hwdec"],
                videoDecoders = video, audioDecoders = audio, displays = desktopDisplays(),
                profileJson = profile?.let { Json { prettyPrint = true }.encodeToString(DeviceProfile.serializer(), it) },
                mpvError = answers.exceptionOrNull()?.let { "libmpv couldn't be loaded" })
            mutable.update { it.copy(report = report, reportLoading = false) }
        }
    }
}
