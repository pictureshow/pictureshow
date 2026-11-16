/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.subtitles

import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SUB-DL: finding subtitles through the server's remote-subtitle API (Jellyfin `RemoteSearch/Subtitles`, which a
 * subtitle provider plugin such as OpenSubtitles backs). The rules here are shared by every platform; the wire calls
 * are in [RemoteSubtitleClient].
 */
@Serializable
data class RemoteSubtitle(
    @SerialName("Id") val id: String = "",
    @SerialName("ProviderName") val providerName: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Format") val format: String? = null,
    @SerialName("Author") val author: String? = null,
    @SerialName("Comment") val comment: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("FrameRate") val frameRate: Double? = null,
    @SerialName("DownloadCount") val downloadCount: Int? = null,
    @SerialName("IsHashMatch") val isHashMatch: Boolean? = null,
    @SerialName("AiTranslated") val aiTranslated: Boolean? = null,
    @SerialName("MachineTranslated") val machineTranslated: Boolean? = null,
    @SerialName("Forced") val forced: Boolean? = null,
    @SerialName("HearingImpaired") val hearingImpaired: Boolean? = null,
    @SerialName("ThreeLetterISOLanguageName") val language: String? = null
)

/** One entry of the server's `Localization/Cultures`, the languages its providers are asked in. */
@Serializable
data class SubtitleCulture(
    @SerialName("DisplayName") val displayName: String = "",
    @SerialName("Name") val name: String? = null,
    @SerialName("TwoLetterISOLanguageName") val twoLetter: String? = null,
    @SerialName("ThreeLetterISOLanguageName") val threeLetter: String? = null,
    @SerialName("ThreeLetterISOLanguageNames") val threeLetterNames: List<String> = emptyList()
)

/** Whether this viewer can search at all, decided before any search so the panel can say why not. */
enum class RemoteSubtitleAccess {
    /** Search away. */
    Allowed,

    /** The account lacks Jellyfin's "Allow subtitle management" permission; the server would answer 403. */
    NotAllowed,

    /** No subtitle provider plugin is installed on the server, so every search would come back empty. */
    NoProvider,

    /** A downloaded copy, Live TV or a recording: nothing on the server to attach a file to. */
    Unavailable
}

/** Why the panel shows no results, in a form each platform turns into its own words. */
enum class RemoteSubtitleEmpty { None, NoMatches, NotAllowed, NoProvider, Failed, Unreachable }

/** The tracks to reopen with once the server lists the downloaded file. Indices are the server's, after the refresh. */
data class AdoptedSubtitle(val subtitleIndex: Int, val audioIndex: Int)

object RemoteSubtitlePolicy {
    /**
     * Admins and accounts with subtitle management may search. A null permission or fetcher list means we couldn't read
     * it; then searching is how we find out, so it's allowed.
     */
    fun access(
        playable: Boolean,
        isAdministrator: Boolean?,
        subtitleManagement: Boolean?,
        subtitleFetchers: List<String>?
    ): RemoteSubtitleAccess = when {
        !playable -> RemoteSubtitleAccess.Unavailable
        isAdministrator != true && subtitleManagement == false -> RemoteSubtitleAccess.NotAllowed
        subtitleFetchers != null && subtitleFetchers.isEmpty() -> RemoteSubtitleAccess.NoProvider
        else -> RemoteSubtitleAccess.Allowed
    }

    /** What an HTTP status from search or download means for the panel. */
    fun failure(status: Int?): RemoteSubtitleEmpty = when (status) {
        null -> RemoteSubtitleEmpty.Unreachable
        401, 403 -> RemoteSubtitleEmpty.NotAllowed
        else -> RemoteSubtitleEmpty.Failed
    }

    /**
     * Best first: a file made for this exact video (hash match), then human translations, then the most downloaded,
     * then the best rated. Ties keep the provider's order.
     */
    fun order(results: List<RemoteSubtitle>): List<RemoteSubtitle> = results
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<RemoteSubtitle>> { it.value.isHashMatch == true }
                .thenBy { it.value.machineTranslated == true || it.value.aiTranslated == true }
                .thenByDescending { it.value.downloadCount ?: 0 }
                .thenByDescending { it.value.communityRating ?: 0.0 }
                .thenBy { it.index }
        )
        .map { it.value }

    /**
     * The facts a row shows under its name, as stable tokens each platform words for itself:
     * `hash-match`, `hearing-impaired`, `forced`, `machine-translated`, `format:<FORMAT>`, `provider:<name>`,
     * `downloads:<n>`. Order is the order they're shown.
     */
    fun details(result: RemoteSubtitle): List<String> = buildList {
        if (result.isHashMatch == true) add("hash-match")
        if (result.hearingImpaired == true) add("hearing-impaired")
        if (result.forced == true) add("forced")
        if (result.machineTranslated == true || result.aiTranslated == true) add("machine-translated")
        result.format?.trim()?.takeIf { it.isNotEmpty() }?.let { add("format:${it.uppercase()}") }
        result.providerName?.trim()?.takeIf { it.isNotEmpty() }?.let { add("provider:$it") }
        result.downloadCount?.takeIf { it > 0 }?.let { add("downloads:$it") }
    }

    /** A row's title: the release name when the provider gives one, otherwise the provider and format. */
    fun title(result: RemoteSubtitle): String {
        val name = result.name?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) return name
        return listOfNotNull(result.providerName?.trim(), result.format?.trim()?.uppercase()).filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { result.id }
    }

    /** Map any language code the server or device uses (2- or 3-letter) to the culture's 3-letter search code. */
    fun searchCode(code: String?, cultures: List<SubtitleCulture>): String? {
        val wanted = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotEmpty() } ?: return null
        val culture = cultures.firstOrNull { culture ->
            culture.threeLetter?.lowercase() == wanted ||
                culture.twoLetter?.lowercase() == wanted ||
                culture.threeLetterNames.any { it.lowercase() == wanted }
        }
        return culture?.threeLetter?.lowercase() ?: wanted.takeIf { it.length == 3 }
    }

    /**
     * The language a search starts in: the viewer's subtitle preference, else their audio preference, else the device
     * language. Each is mapped through the server's cultures; one the server doesn't know is skipped.
     */
    fun initialLanguage(
        subtitlePreference: String?,
        audioPreference: String?,
        deviceLanguage: String?,
        cultures: List<SubtitleCulture>
    ): String = listOf(subtitlePreference, audioPreference, deviceLanguage, "eng")
        .firstNotNullOfOrNull { candidate ->
            searchCode(candidate, cultures)?.takeIf { code -> cultures.isEmpty() || cultures.any { it.threeLetter?.lowercase() == code } }
        } ?: "eng"

    /**
     * Widely used subtitle languages, in Jellyfin's search codes. Listed after the viewer's own so a remote reaches
     * them without scrolling through some 500 cultures in alphabetical order.
     */
    val COMMON_LANGUAGES = listOf(
        "eng", "spa", "fre", "ger", "por", "ita", "dut", "pol", "rus", "ara", "chi", "jpn", "kor", "tur", "swe", "heb"
    )

    /**
     * The language list: the start language and the ones the viewer prefers first, then [COMMON_LANGUAGES], then every
     * other culture by name. Each code appears once.
     */
    fun languageChoices(first: List<String>, cultures: List<SubtitleCulture>): List<SubtitleCulture> {
        val usable = cultures.filter { !it.threeLetter.isNullOrBlank() && it.displayName.isNotBlank() }
            .distinctBy { it.threeLetter!!.lowercase() }
        // Common codes go through the cultures like any preference: Jellyfin 12 searches "fra", not "fre".
        val head = (first + COMMON_LANGUAGES.mapNotNull { searchCode(it, cultures) })
            .mapNotNull { code -> usable.firstOrNull { it.threeLetter!!.lowercase() == code.lowercase() } }
            .distinct()
        return head + usable.filterNot { it in head }.sortedBy { it.displayName.lowercase() }
    }

    /**
     * After a download the server refreshes the item and lists the file as a new external stream. Jellyfin numbers
     * external files before the embedded streams and renumbers everything, so the embedded indices shift too
     * (observed on Jellyfin 12: an item with two external .srt files lists them as 0 and 1, video as 2). So:
     * - the new stream is an external subtitle in [after] that no stream in [before] accounts for, preferring
     *   [language] and then [format];
     * - the selected audio keeps its identity: embedded streams keep their order within their type, external ones
     *   match on what they are.
     * Null while the refreshed list doesn't show a new subtitle yet.
     */
    fun adopt(
        before: List<MediaStream>,
        after: List<MediaStream>,
        selectedAudio: Int,
        language: String?,
        format: String?
    ): AdoptedSubtitle? {
        val mapping = mapStreams(before, after)
        val accounted = mapping.values.toSet()
        val added = after.filter { it.type == MediaStreamType.Subtitle && it.isExternal && it.index !in accounted }
        if (added.isEmpty()) return null
        val wantedLanguage = language?.lowercase()
        val wantedFormat = format?.lowercase()?.let(::subtitleCodec)
        val chosen = added.maxWithOrNull(
            compareBy<MediaStream> { it.language?.lowercase() == wantedLanguage }
                .thenBy { wantedFormat != null && subtitleCodec(it.codec?.lowercase()) == wantedFormat }
                .thenBy { -it.index }
        ) ?: return null
        val audio = if (selectedAudio < 0) selectedAudio else mapping[selectedAudio] ?: -1
        return AdoptedSubtitle(chosen.index, audio)
    }

    /** Old index to new index for every stream in [before] that is still listed. */
    fun mapStreams(before: List<MediaStream>, after: List<MediaStream>): Map<Int, Int> {
        val mapping = mutableMapOf<Int, Int>()
        val types = (before + after).map { it.type }.distinct()
        for (type in types) {
            val oldEmbedded = before.filter { it.type == type && !it.isExternal }.sortedBy { it.index }
            val newEmbedded = after.filter { it.type == type && !it.isExternal }.sortedBy { it.index }
            oldEmbedded.zip(newEmbedded).forEach { (old, new) -> mapping[old.index] = new.index }
            val remaining = after.filter { it.type == type && it.isExternal }.sortedBy { it.index }.toMutableList()
            before.filter { it.type == type && it.isExternal }.sortedBy { it.index }.forEach { old ->
                val match = remaining.firstOrNull { signature(it) == signature(old) } ?: return@forEach
                remaining.remove(match)
                mapping[old.index] = match.index
            }
        }
        return mapping
    }

    private fun signature(stream: MediaStream) = listOf(
        stream.type?.name,
        subtitleCodec(stream.codec?.lowercase()),
        stream.language?.lowercase(),
        stream.title,
        stream.isForced.toString(),
        stream.isHearingImpaired.toString()
    )

    private fun subtitleCodec(codec: String?): String? = when (codec) {
        "srt", "subrip" -> "subrip"
        "ssa", "ass" -> "ass"
        "vtt", "webvtt" -> "webvtt"
        else -> codec
    }
}
