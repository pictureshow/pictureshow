# Application vector operations

`application.json` uses the shared envelope `{ "schemaVersion": 1, "suite": "application", "cases": [...] }`.
Each case has a stable `id`, `operation`, `input`, exact `expected` JSON value, and `source` pointing to the Android
reference or its existing tests. Compare JSON structurally: object member order is immaterial, array order, null,
integers, enum spelling, and string values are significant. The Kotlin runner invokes the production core policy,
collects all assertion/operation failures, and reports them together after every case has run. Cancellation still
propagates. A malformed envelope or duplicate IDs fails before execution.

The Android application delegates these extracted functions to core. `PlaybackPreferences`, `SubtitleSize`, and
`SubtitleBackground` retain Kotlin source names through app typealiases; their runtime class names now belong to
core. Persisted enum names and primitive preference keys remain unchanged. This is not a binary compatibility
promise for separately compiled consumers of app classes.

## `settings`

Input is a raw stored-preference object. All members are optional or nullable:

```json
{
  "bitrateMbps": 40,
  "passthrough": false,
  "subtitleSize": "ExtraLarge",
  "subtitleBackground": "Box",
  "segmentModes": { "Intro": "Automatic", "Outro": "Off" }
}
```

Expected contains `bitrateMbps`, `passthrough`, `subtitleSize`, `subtitleBackground`, `segmentModes`,
`maxStreamingBitrate`, and `passthroughEnabled`. The bitrate choices are null (Auto), 120, 80, 40, 20, 10, and 4 Mbps;
invalid persisted choices decode as Auto. Auto is an explicit 200,000,000 bits/second protocol ceiling. A finite
choice is multiplied by 1,000,000. Null or absent passthrough decodes as true. This is the **app preference default**;
the lower-level `PlaybackSettings()` capability model defaults passthrough to false until a caller provides its
actual preference. A platform must not accidentally adopt that lower-level default for its settings screen.

Subtitle sizes are `System`, `Small`, `Normal`, `Large`, and `ExtraLarge`; backgrounds are `System`, `None`, `Shadow`,
and `Box`. Invalid/missing enum strings decode as `System`. Segment keys are `Intro`, `Outro`, `Recap`, `Preview`,
and `Commercial`; each decodes to `Ask`, `Automatic`, or `Off`, with `Ask` for invalid/missing values. The complete
five-key map appears in expected. The unknown segment type has no setting and is ignored.

This operation tests decoding and effective profile settings, not subtitle platform rendering or disk I/O.

## `validate-bitrate`

Input: `{ "bitrateMbps": null }` or an integer choice. Expected: `{ "valid": true }` or `{ "valid": false }`.
The same set of choices above is accepted when writing app preferences; an invalid write is rejected rather than
silently persisted. This differs intentionally from recovery when decoding corrupted stored values.

## `default-stream`

Input: `streams` (an array of Jellyfin `MediaStream` objects), `type` (`Audio` or `Subtitle`), nullable `serverIndex`,
and `preferredLanguage` (string). Expected is an integer server stream index, or `-1` for no track.

For subtitles, the server index wins directly, including `-1`; a null index means off. The client must not enable
a default/forced/language-matching subtitle when Jellyfin has selected none. For audio, a non-null server index
wins, followed by the first matching-type stream marked `IsDefault`, followed by the first matching-type stream
whose normalized language matches the preference, followed by the first audio stream. An empty list yields `-1`.
Server indices are authoritative even if absent from the supplied stream list; index validation/mapping to the
platform demuxer is a later concern. The server's default audio index carries its user audio-language and remembered
selection decisions; client language fallback does not override it.

The `tracks.prepared-menu.current-source-server-index` fixture uses this operation to verify only the current
source's selected subtitle index. Its native preparation/lifecycle source references are acceptance provenance, not
additional operations executed by the vector runner. Eager preparation of both menus off the UI thread, reuse across
opens, source-generation invalidation, stale-worker rejection, and cancellation of pending opens on Back or stop are
PLAY-08 native acceptance requirements in [player.md](../features/player.md). Android's executable application-vector
gate is `ApplicationParityVectorsTest` under `:core:jvmTest`; it does not drive `PlayerScreen`, Compose or lifecycle
callbacks. Track entry focus, the native modal boundary and opener return additionally require the track
focus/accessibility suites and installed-device acceptance; this core index-policy vector does not execute them or
establish installed TalkBack speech/focus. The `panel-open title=... inActivityWindow=...` diagnostic is a first-layout
timing receipt, not an accessibility or presentation oracle. Keep native preparation/input/lifecycle acceptance
alongside the vector gate on each platform.

The JVM reference normalizes with Java `Locale`: blank and literal `und` become null; underscores become hyphens;
`Locale.forLanguageTag(value).isO3Language` supplies the ISO 639-2 language, and a thrown conversion falls back to
`value.lowercase()`. Region tags such as `fr-CA` match `fra`, while `en` matches `eng`. These language conversions
must be reproduced by equivalent platform APIs/tables. Locale edge cases beyond these committed vectors remain
subject to the Android reference; do not infer that this is a generic BCP 47 validation operation.

## `resume-start`

Input: integer `ticks`, boolean `resume`, and nullable integer `explicitPositionMs`. Expected is integer milliseconds.
A non-null, already-validated explicit position wins, including zero. Otherwise resume uses
`max(ticks, 0) / 10000`, truncating the fractional millisecond; Play from start (`resume: false`) uses zero.
This policy is called by normal navigation, external-entry resolution, and next-episode playback. It does not
replace item authorization or external URI/position validation. In particular, an episode selected as next can
resume its existing saved position; it is not unconditionally restarted.

## `caption-language`

Input: boolean `enabled` (system captions), nullable `configuration` (whole Jellyfin configuration fields), and string
`tvLanguage` (platform locale). Expected is a preferred-language string or null. Captions off, an unavailable/failed
configuration read, or a case-insensitive SubtitleMode `None` all return null, leaving the server's original selection
in charge. Otherwise use a nonblank SubtitleLanguagePreference before the TV locale. Other modes, including
OnlyForced, permit system-caption fallback when the server supplies no selection. The selected language is returned verbatim here; normalization
occurs when matching tracks. This opt-in system-caption policy precedes negotiation, while `default-stream` maps the
server's resulting default index after negotiation. It does not override an explicit per-playback Off choice.

## `caption-index`

Input: `source` (Jellyfin MediaSourceInfo, including MediaStreams and nullable DefaultSubtitleStreamIndex), and string
`preferredLanguage`. Expected is a subtitle index, -1 for retained Off, or null when no selection can be made.
First retain -1 or any nonnegative server default that identifies an actual subtitle stream. Retention precedes
language normalization and ranking: the server may be returning a viewer's choice, including another language,
an unknown language, a forced track or an image track. Audio/video and absent stream indices are not usable defaults.

Only when there is no usable default, match subtitle streams using normalized language keys, then sort by:

1. Full captions (`IsForced=false`) before forced-only translations.
2. Hearing-impaired tracks before other tracks.
3. Text before image, using IsTextSubtitleStream or a known text codec.
4. `IsDefault=true` before other streams.
5. Lowest numerical index.

No language match or an unknown/blank preferred language returns null in that fallback. Jellyfin 12 represents
remembered Off as -1 and computed no-default as null/omitted; preserve that distinction. Server defaults do not reveal
whether a remembered choice or language/default rules produced them, so keep valid defaults in both cases. A server
image default can therefore require burn-in even when text exists. Per-device choice history would compete with
cross-client choices and is not used.

DeliveryMethod does not rank tracks; a fallback choice must be renegotiated with Jellyfin so extraction or burn-in
is decided by the server. The pure choice is covered here; source/resume preservation and panel pick -> Stop ->
reopen are app acceptance tests in CaptionSubtitlesTest and TrackReportingSessionTest. ServerPlaybackGateway bypasses
the override for explicit per-playback indices, including -1. A fallback differing from the server default triggers
a second PlaybackInfo pinned to that source and index, retaining start/flags/capabilities; the first decision is not
opened before negotiation succeeds. A retained default needs no caption-override request. Reopening still has
automatic provenance and omits subtitle indices from reports until the viewer picks in that viewing (PLAY-20).

Matching trims and lowercases language input, then uses the same JVM normalization described under `default-stream`.
It additionally maps every ISO 639-2 bibliographic alias to its terminology form:

| Bibliographic | Terminology | Bibliographic | Terminology |
| --- | --- | --- | --- |
| alb | sqi | arm | hye |
| baq | eus | bur | mya |
| chi | zho | cze | ces |
| dut | nld | fre | fra |
| geo | kat | ger | deu |
| gre | ell | ice | isl |
| mac | mkd | mao | mri |
| may | msa | per | fas |
| rum | ron | slo | slk |
| tib | bod | wel | cym |

An individual vector covers each alias so removing one mapping produces a named failure. Both the chosen preference
and each stream language use this mapping; region and underscore forms such as `fr_CA` are normalized too.

## `end-position`

Input: integer `positionMs` and `durationMs`, both read from the same player/stream timeline. Expected is
`{ "bounded": integer, "completed": integer }`. Bounded position clamps negative positions to zero and, only when
stream duration is positive, caps renderer overrun at that duration. Unknown or negative duration does not cap the
physical position. A confirmed completion rounds bounded position up to duration only when the stream duration is
plausible (`1..86400000` ms, inclusive) and the remaining difference is within `0..1000` ms, inclusive. Overstated
container timelines above that ceiling remain at physical EOS even when very close to the declared end. Earlier
physical endings more than one second away retain their real position. Server catalog runtime is not an input.

The app retains its internal function wrappers and Player-dependent `boundedPlaybackPosition`; both pure policies
now delegate to common core. Server-seek player wrappers must expose absolute item time consistently before invoking
this operation, as described in the playback feature manifest. This operation supplies final reporting/spoken-clock
positions; it does not establish that physical EOS has occurred.

## `active-segment`

Input: `segments` (Jellyfin `MediaSegment` objects), integer `positionMs`, and `modes` mapping type names to enum names.
Expected is the chosen segment ID string or null. The interval is start-inclusive and end-exclusive. Off, Unknown,
negative start, end not after start, end beyond 24 hours, and implausibly long segments never produce a seek.
Intro/Recap/Preview are limited to ten minutes; Outro/Commercial to thirty. Among eligible overlaps the earliest
`StartTicks` wins, retaining source order on ties. Missing modes use Ask behavior; Automatic remains eligible.

## `next-trigger`

Input: integer `durationMs` and `segments`. Expected is integer milliseconds or null. Unknown/nonpositive duration
yields null. A plausible Outro that starts before the duration triggers at its start, even when its end exceeds the
runtime. Otherwise trigger at `max(durationMs - 10000, 0)`. Malformed/implausible credits never cut hours of story.
This operation establishes eligibility timing; UI pause, cancellation, auto-play configuration, countdown, and
physical playback-end guards are specified in the feature manifest.

## `next-countdown`

Input: integers `wallSeconds` and `mediaLeftMs`, number `speed`, booleans `creditsCountdown`, `autoPlay`, `ended`,
`suspended`. Expected is the integer a touch next card shows, or null for no number. Null when `autoPlay` is false, or
when `suspended` and not `ended`. A nonpositive `mediaLeftMs` or `speed` keeps `wallSeconds`. Otherwise
`toEnd = ceil(mediaLeftMs / speed / 1000)`, and the result is `min(wallSeconds, toEnd)` with a credits countdown and
`toEnd` without one. See TOUCH-13.

## `grid-vertical-move`

Input: integers `index`, `columns`, `count`, boolean `down`, optional boolean `loadingMore` (default false; true while a
page is on its way). Expected is the index a vertical D-pad move lands on in a grid laid out row by row, or null when it
lands on no card. Up is `index - columns`, null from the first row (the caller decides what is above the grid). Down is
`index + columns`. When that passes the end: null while `loadingMore`, where the press is held. Otherwise the last item
if it is on a later row, and null from the last row. Null when `columns` is not positive or `index` is outside
`0 until count`. Null can mean three different things here, and `grid-navigation.json` tells them apart. See CAT-19.

Owed: web runs these. Apple has no vector runner on main, so `grid-vertical-move` is owed there with the rest of the
application suite.

## `grid-navigation.json`: `grid-vertical-outcome`, `grid-hold-release`, `grid-strip-detail`

`grid-vertical-outcome` has the input of `grid-vertical-move`, with `loadingMore` required. Expected is one of
`{"outcome":"card","index":n}`, `{"outcome":"hold"}` (the press waits for the page), `{"outcome":"stay"}` (the press is
spent and focus stays) and `{"outcome":"above"}` (Up from the first row).

A hold has an end. `grid-hold-release` is where it goes: input `index`, `columns`, `count` (loaded by then) and
`releasedBy`, one of `page`, `empty-page`, `failure` and `cap`. The result doesn't depend on `releasedBy`, and it is a card
or a stay, never another hold: a page landing in the column, and otherwise the last card if it is on a later row. The cap is
1.5 s from the first press, and a repeating key doesn't restart it. After it, a late page leaves focus where the viewer
is. Any other key, a sort change by any route (a screen reader's click included) and focus moving drop the hold with no
landing. A newer key also replaces a move still waiting on a card to compose, and a repeat of the same key doesn't.

`grid-strip-detail`: input `sortBy`, boolean `descending`; expected the pinned strip's words for the sort in force, in the
sort chips' own titles. An unknown `sortBy` reads as the first sort.

Owed: Android runs these. Web lists the suite in `pendingPortSuites`, and it shows as todo there until ported. Apple has no
vector runner on main, so it owes the suite with the rest.

## `next-adjacency`

Input: a Jellyfin `item` and an `items` array representing the mocked server adjacency response. Expected is the next
item ID string or null. Non-Episode items or episodes without a nonblank SeriesId make no request. Otherwise the
reference calls `Shows/{series}/Episodes` with `adjacentTo`, `isMissing=false`, `enableUserData=true`, `fields=Overview`,
and the account `userId`. The missing filter is sent **before server adjacency selection**. The client finds the
current item and accepts only its immediate following item if it is a different Episode. It keeps server order,
including inserted specials and season boundaries. Missing current, duplicate current, non-Episode successor, and
end-of-series produce null. These fixtures use `series` and `current` so the runner also asserts the exact request.

## `user-configuration`

Input: `fields` (the whole server configuration object) and optional/null `change` with string `name` and `value`.
Expected: `audioLanguage`, `subtitleLanguage`, `subtitleMode`, and full `fields`. Null/absent language reads as an empty
string; absent/null subtitle mode reads as `Default`. A change uses the production `withPreference` operation and
preserves every unrelated and future field. Editable names are AudioLanguagePreference, SubtitleLanguagePreference,
and SubtitleMode. These are server preferences, not local capability flags.

## Integer precision

Jellyfin ticks are signed 64-bit integers in units of 100 ns; milliseconds and ticks are distinct. This suite's JSON
number fixtures are within the exact JavaScript integer range (`abs(n) <= 9007199254740991`), including its large
resume boundary. Parse them without floating-point rounding. Future cases outside that range must use decimal-string
fields and update all platform adapters explicitly; silently rounding an Int64 through JavaScript `Number` is not
parity. Integer expected values are exact, and division truncates according to the operation above.
