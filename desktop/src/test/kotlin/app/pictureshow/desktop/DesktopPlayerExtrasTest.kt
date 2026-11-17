/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.core.subtitles.*
import java.net.InetAddress
import java.util.Properties
import kotlin.test.*

/** Stream A's pure rules: segments, labels, mpv options, stored settings, still-watching and network class. */
class DesktopPlayerExtrasTest {
    private val intro = MediaSegment("intro", SegmentType.Intro, 50_000_000, 200_000_000)
    private val credits = MediaSegment("credits", SegmentType.Outro, 5_000_000_000, 5_400_000_000)
    private val extras = DesktopPlayerExtras(segments = listOf(intro, credits))

    @Test fun segmentAskOffersAButtonAutomaticSkipsOnceAndOffDoesNothing() {
        val ask = mapOf(SegmentType.Intro to SegmentMode.Ask)
        assertEquals(DesktopSegmentAction.Ask(intro), desktopSegmentAction(extras, 6_000, ask))
        assertEquals(DesktopSegmentAction.None, desktopSegmentAction(extras, 20_000, ask), "the end is exclusive")
        assertEquals(DesktopSegmentAction.None, desktopSegmentAction(extras, 4_000, ask))
        val auto = mapOf(SegmentType.Intro to SegmentMode.Automatic)
        assertEquals(DesktopSegmentAction.Skip(intro), desktopSegmentAction(extras, 6_000, auto))
        assertEquals(DesktopSegmentAction.None, desktopSegmentAction(extras.copy(autoSkipped = setOf("intro")), 6_000, auto), "Automatic skips each segment once")
        assertEquals(DesktopSegmentAction.None, desktopSegmentAction(extras, 6_000, mapOf(SegmentType.Intro to SegmentMode.Off)))
        assertEquals(DesktopSegmentAction.Ask(intro), desktopSegmentAction(extras, 6_000, emptyMap()), "a type with no setting asks")
    }

    @Test fun labelsMatchAndroidAndChaptersNumberTheUnnamed() {
        assertEquals("Skip intro", desktopSkipLabel(SegmentType.Intro))
        assertEquals("Skip credits", desktopSkipLabel(SegmentType.Outro))
        assertEquals("Skip recap", desktopSkipLabel(SegmentType.Recap))
        assertEquals("Opening · 0:00", desktopChapterLabel(Chapter(0, "Opening"), 0))
        assertEquals("Chapter 3 · 1:01:01", desktopChapterLabel(Chapter(36_610_000_000, "  "), 2))
        val chapters = listOf(Chapter(0, "A"), Chapter(300_000_000, "B"), Chapter(600_000_000, "C"))
        assertEquals(0, desktopCurrentChapter(chapters, 29_999))
        assertEquals(1, desktopCurrentChapter(chapters, 30_000))
        assertEquals(2, desktopCurrentChapter(chapters, 3_600_000))
        assertEquals(0, desktopCurrentChapter(emptyList(), 10))
    }

    @Test fun onlyAServerDrawnSubtitleIsBurnedIn() {
        val streams = listOf(MediaStream(type = MediaStreamType.Subtitle, index = 2, deliveryMethod = SubtitleDeliveryMethod.Encode),
            MediaStream(type = MediaStreamType.Subtitle, index = 3, deliveryMethod = SubtitleDeliveryMethod.External))
        fun decision(method: PlayMethod) = PlayDecision("m", MediaSourceInfo(id = "s", mediaStreams = streams), method, "u", emptyMap(), "p")
        assertTrue(desktopSubtitleBurnedIn(decision(PlayMethod.Transcode), 2))
        assertFalse(desktopSubtitleBurnedIn(decision(PlayMethod.Transcode), 3))
        assertFalse(desktopSubtitleBurnedIn(decision(PlayMethod.DirectPlay), 2))
        assertFalse(desktopSubtitleBurnedIn(decision(PlayMethod.Transcode), -1))
        assertEquals(SubtitleTiming.Section.BurnedIn, SubtitleTiming.section(true, desktopSubtitleBurnedIn(decision(PlayMethod.Transcode), 2)))
    }

    @Test fun defaultsSetNoMpvOptionsSoPlaybackIsUnchanged() {
        assertEquals(emptyList(), desktopMpvOptions(desktopDefaultPlayback()))
        assertFalse(desktopDefaultPlayback().passthrough, "desktop can't detect a receiver, so passthrough starts off")
    }

    @Test fun subtitleChoicesBecomeMpvOptions() {
        val prefs = desktopDefaultPlayback().copy(subtitleSize = SubtitleSize.Large, subtitleBackground = SubtitleBackground.Box,
            subtitleStyle = SubtitleStyle(foreground = "Yellow", textOpacity = 50, backgroundColor = "Black", backgroundOpacity = 100, position = "Top", bold = true, overrideEmbedded = true))
        val options = desktopMpvOptions(prefs).toMap()
        assertEquals("1.25", options["sub-scale"])
        assertEquals("background-box", options["sub-border-style"])
        assertEquals("#FF000000", options["sub-back-color"])
        assertEquals("#80FFFF00", options["sub-color"])
        assertEquals("10", options["sub-pos"])
        assertEquals("yes", options["sub-bold"])
        assertEquals("force", options["sub-ass-override"])
        assertNull(options["sub-italic"])
    }

    @Test fun passthroughListsOnlyAllowedFormatsAndDownmixWins() {
        val on = desktopDefaultPlayback().copy(passthrough = true)
        assertEquals(listOf("ac3", "eac3", "dts", "dts-hd", "truehd"), desktopSpdifCodecs(on))
        assertEquals("ac3,eac3,dts,dts-hd,truehd", desktopMpvOptions(on).toMap()["audio-spdif"])
        val noAtmos = on.copy(tuning = on.tuning.copy(disabledPassthrough = setOf(PassthroughEncoding.EAC3_JOC, PassthroughEncoding.DTS)))
        assertEquals(listOf("ac3", "dts-hd", "truehd"), desktopSpdifCodecs(noAtmos), "mpv can't tell Atmos from DD+, so E-AC-3 stays decoded")
        val downmix = on.copy(tuning = on.tuning.copy(downmix = true))
        assertEquals(emptyList(), desktopSpdifCodecs(downmix))
        assertEquals("stereo", desktopMpvOptions(downmix).toMap()["audio-channels"])
        assertEquals("5.1,stereo,mono", desktopMpvOptions(on.copy(tuning = on.tuning.copy(maxAudioChannels = 6))).toMap()["audio-channels"])
    }

    @Test fun profileCarriesTheCapAndChannelLimit() {
        val base = DeviceProfile(name = "t", maxStreamingBitrate = 1, maxStaticBitrate = 1,
            transcodingProfiles = listOf(TranscodingProfile(container = "ts", type = DlnaProfileType.Video, videoCodec = "h264", audioCodec = "aac", protocol = MediaStreamProtocol.Http, maxAudioChannels = "6")))
        val prefs = desktopDefaultPlayback().copy(tuning = PlaybackTuning(downmix = true))
        val out = base.forDesktopPreferences(prefs, 8_000_000)
        assertEquals(8_000_000, out.maxStreamingBitrate); assertEquals(8_000_000, out.maxStaticBitrate)
        assertEquals("2", out.transcodingProfiles.single().maxAudioChannels)
        assertEquals("6", base.forDesktopPreferences(desktopDefaultPlayback(), 1).transcodingProfiles.single().maxAudioChannels, "no limit keeps the profile's")
    }

    @Test fun storedPlaybackRoundTripsAndBadValuesFallBackOneByOne() {
        val prefs = desktopDefaultPlayback().copy(passthrough = true, subtitleSize = SubtitleSize.Small,
            segmentModes = desktopDefaultPlayback().segmentModes + (SegmentType.Intro to SegmentMode.Automatic) + (SegmentType.Outro to SegmentMode.Off),
            tuning = PlaybackTuning(rewindSeconds = 10, nextEpisodeCountdownSeconds = 5, stillWatching = StillWatching.TwoEpisodes, postCredits = PostCredits.NextEpisode),
            subtitleStyle = SubtitleStyle(foreground = "Cyan", bold = true))
        val p = Properties(); writeDesktopPlayback(p, prefs)
        val back = readDesktopPlayback(p)
        assertEquals(prefs.passthrough, back.passthrough); assertEquals(prefs.subtitleSize, back.subtitleSize)
        assertEquals(prefs.segmentModes, back.segmentModes)
        assertEquals(prefs.tuning.normalized(), back.tuning); assertEquals(prefs.subtitleStyle.normalized(), back.subtitleStyle)

        p.setProperty("subtitleSize", "Enormous"); p.setProperty("segment.intro", "Sometimes"); p.setProperty("tuning", "{not json")
        val bad = readDesktopPlayback(p)
        assertEquals(SubtitleSize.System, bad.subtitleSize); assertEquals(SegmentMode.Ask, bad.segmentModes[SegmentType.Intro])
        assertEquals(PlaybackTuning().normalized(), bad.tuning); assertEquals(SegmentMode.Off, bad.segmentModes[SegmentType.Outro], "other values survive")
        assertEquals(SubtitleStyle(foreground = "Cyan", bold = true).normalized(), bad.subtitleStyle)
        assertFalse(readDesktopPlayback(Properties()).passthrough)
    }

    @Test fun stillWatchingCountsAutomaticStartsAndIdleTimeAndInputResetsBoth() {
        var now = 0L
        val input = DesktopViewerInput { now }
        assertFalse(input.due(StillWatching.TwoEpisodes))
        input.automaticallyAdvanced()
        assertTrue(input.due(StillWatching.TwoEpisodes), "the next would be the second in a row")
        assertFalse(input.due(StillWatching.ThreeEpisodes)); assertFalse(input.due(StillWatching.Off))
        input.input(); assertFalse(input.due(StillWatching.TwoEpisodes))
        now = 59 * 60_000_000_000L; assertFalse(input.due(StillWatching.TwoEpisodes))
        now = 60 * 60_000_000_000L; assertTrue(input.due(StillWatching.TwoEpisodes), "an hour with no key press")
        input.input(); assertFalse(input.due(StillWatching.TwoEpisodes))
    }

    @Test fun aServerOnThisMachineOrLinkIsHomeAnythingElseRemoteAndNeverMetered() {
        fun at(vararg ip: String) = { _: String -> ip.map { InetAddress.getByName(it) } }
        assertEquals(PlaybackNetwork.Home, desktopPlaybackNetwork("http://127.0.0.1:8096", at("127.0.0.1")))
        assertEquals(PlaybackNetwork.Home, desktopPlaybackNetwork("http://media.invalid", at("fe80::1")))
        assertEquals(PlaybackNetwork.Home, desktopPlaybackNetwork("http://media.invalid", at("fd12:3456::1")))
        assertEquals(PlaybackNetwork.Remote, desktopPlaybackNetwork("https://media.invalid", at("203.0.113.7")))
        assertEquals(PlaybackNetwork.Remote, desktopPlaybackNetwork("https://media.invalid", at("fd12:3456::1", "203.0.113.7")), "every address must be local")
        assertEquals(PlaybackNetwork.Remote, desktopPlaybackNetwork("https://media.invalid") { error("no DNS") })
    }

    @Test fun subtitleResultsReadInTheViewersWords() {
        val r = RemoteSubtitle(id = "x", providerName = "Open", format = "srt", downloadCount = 1234, isHashMatch = true, hearingImpaired = true)
        assertEquals("Made for this video · Hearing impaired · SRT · Open · 1,234 downloads", desktopSubtitleDetails(r))
        assertEquals("1 download", desktopSubtitleDetails(RemoteSubtitle(id = "y", downloadCount = 1)))
        assertEquals("Checking what your server can do…", desktopSubtitleStatus(RemoteSubtitleState(phase = RemoteSubtitlePhase.Checking)))
    }
}
