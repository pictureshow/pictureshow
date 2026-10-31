# Recovery and seek vector operations

`recovery.json` has schema version 1 and suite `recovery`. `RecoveryParityVectorsTest` executes every case against the production `AudioOnlyRecovery`, `TranscodeSeek`, or `PlaybackClient.prepare` implementation. Expectations were derived from the existing core regression tests linked by each case's `source`. They are committed inputs and outputs, with no snapshot update mode. The test collects case failures and propagates cancellation.

Each case has `id`, `operation`, `input` and exact `expected`. Ordinary JSON structural comparison applies: object member order is immaterial; array order, null, enum spelling, integer values and strings matter. No operation requires a running server.

## Decision input

The pure-policy operations receive a `decision` containing:

- `itemId`, `method` (`DirectPlay`, `DirectStream`, `Transcode`), absolute `url`, nullable `playSessionId`, `headers` and `transcodeReasons`.
- `source`, a Jellyfin wire-format `MediaSourceInfo` object. Its property names retain Jellyfin casing, such as `RunTimeTicks`, `MediaStreams`, `DefaultAudioStreamIndex`, and `DefaultSubtitleStreamIndex`. Media stream fields and enum serial names likewise retain their protocol representation.

The adapter constructs a real `PlayDecision`; omitted source fields use the production DTO defaults. It does not synthesize a playback outcome or emulate a URL rewrite.

## URL projection

For URL-producing operations, compare this normalized structural projection of the **actual output URL**:

```json
{
  "scheme": "https",
  "host": "media.example.test",
  "port": 443,
  "path": "/jellyfin/Videos/film/stream.ts",
  "query": { "StartTimeTicks": ["123450000"], "Static": ["false"] }
}
```

`path` is the encoded path and `query` is decoded names mapped to all decoded values in their original order. Object member order does not matter; exact parameter spelling and duplicate values remain visible. `port` is the effective port, including the scheme default. This tests URL meaning without making the order of unrelated query keys a platform requirement. Use a URL parser that preserves repeated parameters; flattening each parameter to one string can hide conflicting stale values. The other vector suites also include exact URL strings for ordinary stream and image construction.

## `seek-required`

Input: `decision`. Expected: boolean from `TranscodeSeek.required`.

The transport repair applies to finite positive-duration, nonlive Transcode playback with HLS protocol, TS output container, and a known `/master.m3u8` endpoint. It excludes `VideoCodec=copy`. Source real frame rate takes precedence over average frame rate, and URL `Framerate` takes precedence over `MaxFramerate`. A source above 30 fps with a positive output cap more than .001 fps below it is eligible. This is transport eligibility; it does not initiate playback or perform a seek.

## `seek-start`

Input: `decision`, integer `targetMs`. Expected: integer from `TranscodeSeek.startPositionMs`.

Clamp the server request start between zero and `max(runtimeTicks / 10000 - 5000, 0)`. The player retains any remaining requested displacement. The last five seconds are deliberately excluded from the server's fast-seek start.

## `seek-url`

Input: `decision`, integer `startMs`. Expected: URL projection from `TranscodeSeek.url`.

Replace only the final endpoint with `stream.ts`, preserving scheme, authority, server base path, negotiated quality, selected tracks and other query values. Replace old StartTimeTicks, Static, CopyTimestamps and SubtitleMethod keys case-insensitively. Write a nonnegative start in ticks, `Static=false` and `CopyTimestamps=false`. Preserve the existing subtitle method or default it to External. Strip legacy token query parameters; never move the authorization header into a URL.

If audio already requires encoding, remove existing AllowAudioStreamCopy case variants and set it false. Otherwise preserve its copy eligibility and channel limit. This prevents repairing video timing by introducing a new lossy audio conversion.

The operation tests the URL helper directly, including a negative start clamped to zero. The application chooses this transport only when its eligibility and session guards allow it.

## `audio-encodes`

Input: `decision`. Expected: boolean from `TranscodeSeek.audioAlreadyEncodes`.

Use URL AudioStreamIndex before the source default. No audio tracks or explicit index -1 requires no audio tradeoff and returns true; an unknown selected index returns false. Explicit target codec `copy` returns false before compatibility reasons are considered. Disabled audio-copy flags or the AudioChannelsNotSupported server reason establish an existing encode. Otherwise compare the selected source codec with the target codec set and compare its channels with the first available channel limit in this order: `{codec}-audiochannels`, MaxAudioChannels, AudioChannels, TranscodingMaxAudioChannels. Missing information must not invent an encode requirement.

## `preserve-video`

Input: `decision`, nullable integer `subtitleStreamIndex`. Expected: projected outcome.

A playable outcome contains `method`, nullable `mediaSourceId` and `playSessionId`, unchanged `headers` and `transcodeReasons`, and the output URL projection. An unplayable outcome contains `{ "unplayable": "NO_COMPATIBLE_SOURCE" }`.

DirectPlay/DirectStream decisions pass through unchanged. For Transcode, choose the explicit subtitle index, then the source default, then -1. An active subtitle that requires burn-in, either in its stream delivery metadata or the URL SubtitleMethod, is rejected: audio recovery cannot silently drop captions or encode working video. Explicit -1 bypasses the active-caption check even when the server has left a stale SubtitleMethod=Encode parameter. The helper preserves unrelated parameters; it does not independently rewrite a stale subtitle index. Accepted recovery removes all VideoCodec and AllowVideoStreamCopy case variants and appends one VideoCodec=copy and one AllowVideoStreamCopy=true.

## `prepare-preserve-video`

Input: `request` and a mocked Jellyfin PlaybackInfo `response`. The shared `context` contains the explicit server, account, client identity and baseline device capabilities for this operation. All request flags, track indices and millisecond start position are input values. The adapter calls **real `PlaybackClient.prepare`**, including `requireVideoStreamCopy=true` after server negotiation, with one mocked HTTP response.

Expected has `request` and `outcome`. The request projection checks these fields from the body actually sent: UserId, MaxStreamingBitrate, StartTimeTicks, MediaSourceId, AudioStreamIndex, SubtitleStreamIndex, EnableDirectPlay, EnableDirectStream, AllowVideoStreamCopy, and AllowAudioStreamCopy. It also verifies exactly one POST to the PlaybackInfo endpoint. Full capability-profile serialization is covered by the profile/request vectors in the reference suite; this operation isolates how recovery flags affect the resulting real decision. Outcome has the same projection as `preserve-video`, including the account-scoped authorization header.

The two integration cases prove that recovery keeps selected tracks and the 90123ms offset, forces only audio conversion while preserving video, and refuses an active burn-in subtitle. The request's requireVideoStreamCopy flag is a client policy and is not a Jellyfin JSON field.

All fixtures use documented example domains and synthetic credentials. All tick values in this suite are exactly representable by JavaScript integers; retain integer arithmetic when converting between ticks and milliseconds.
