# Settings and preference honor matrix

Sources: `PlaybackPreferences.kt`, `PlaybackSettingsScreen.kt`, `SubtitlePresentation.kt`, `CaptionSubtitles.kt`, `PassthroughLabels.kt`, `PlayerScreen.kt`; core `UserConfiguration.kt`, `playback/PlaybackClient.kt`, `playback/PlayDecision.kt`, `playback/DeviceProfileBuilder.kt`. Tests: `PlaybackSettingsTest`, `PlaybackSettingsModelTest`, `PassthroughSettingTest`, `SubtitlePresentationTest`, `CaptionSubtitlesTest`, `UserConfigurationTest` and core playback tests.

| ID | Setting/action | Defaults, validation and acceptance |
|---|---|---|
| SET-01 | Max streaming bitrate | Auto (explicit 200,000,000 bit/s effective limit), 120/80/40/20/10/4 Mbps. Persist only supported options; bad stored value falls back to Auto. Repeated TV center cycles choices; touch selection uses a chooser. Effective profile/request obeys selected cap on next player open. |
| SET-02 | Audio passthrough | Default On, boolean; display currently accepted encoded formats, or no encoded formats honestly. Off removes encoded passthrough while preserving decodable PCM paths. User preference is permission, not proof the current sink accepts DTS/TrueHD/etc. |
| SET-03 | Subtitle size | Default System; System, Small, Normal, Large, ExtraLarge. Explicit scales 0.75/1/1.25/1.5; System uses valid positive finite caption scale or 1. Invalid stored enum→System. Text overrides authored font size when explicit; bitmap authored appearance remains. |
| SET-04 | Subtitle background | Default System; System, None, Shadow, Box. None uses transparent/no edge; Shadow uses transparent/drop shadow; Box uses 80% black/no edge. Invalid stored enum→System. System preserves system/authored styling; overrides retain foreground/typeface where supported. |
| SET-05 | Segment actions | Intro, Recap, Preview, Commercial, Outro each Ask (default), Automatic (“Skip automatically”), Off. Unknown/invalid stored mode→Ask. Ask shows skip button; Auto skips; Off does not. Applies next player open. |
| SET-06 | Audio language | Server preference; choice “No preference”, eng, spa, fra, deu, jpn, zho, kor, por, ita, rus, ara, hin. Save full configuration preserving unknown fields. This affects playback through server-selected default audio indices. |
| SET-07 | Subtitle language | Same language choices; server preference. System captions can use this preferred language, otherwise device locale, subject to mode None; server default index remains another source of selection. Exact precedence is in vectors. |
| SET-08 | Subtitle mode | Server preference Default/Always/OnlyForced/None/Smart, absent→Default. Save unchanged unknown configuration fields. None suppresses the Android caption-language override; other modes have no distinct client-side selection algorithm. Server default indices may already incorporate these policies. |
| SET-09 | Preference load/save failure | Local choices save immediately; server controls unavailable while loading/saving, read-only Loading label until fetched. Failed server write preserves last accepted value; error/Retry remains usable. Unauthorized response expires matching account. Failed local persistence shows error without claiming success. |
| SET-10 | Navigation and reading | Support Pictureshow, About Pictureshow, Troubleshooting→This TV, Back. TV initial bitrate focus; Back from child restores exact child opener. Each value is one named change-value action with state. Explanations and headings remain readable with large type. Touch adds Accounts and native switch/choice dialogs, same preferences; its Troubleshooting opens “This phone” or “This tablet” (smallest width 600 dp and up), and SET-11 reads as a plain label with a native switch. |
| SET-11 | Conversion notice | Default On, boolean, stored locally (`playback_conversion_notice` on Android); missing value→On. Off suppresses the PLAY-27 notice only; Playback info still explains conversion. Its own row among the local player preferences, directly after Audio passthrough and before Subtitle size, labelled “Conversion notice: On/Off” with one caption under it: “Shows a short note when the server has to convert a video or a song for this TV.” (each platform names its own device; a platform without music yet says “a video”). One named toggle with On/Off state. Applies the next time the player opens. |
| SET-12 | Google Cast | Default On, boolean, stored locally, on platforms with a Cast sender. Off stops this device's search for Cast devices and shows the chooser's Turn on action (CAST-01); it disconnects from a viewing on a Cast device and leaves that viewing playing, drops a Reconnect or a pick still waiting on one, and Reconnect isn't offered until it's on again. The row always shows the stored value, and changing it stores the change, even while Google Play services need an update; then its caption says so and an action opens the update. No row where Cast can't be used on this device. |

## Server-side settings inventory

This reconciles the settings-parity audit against Android code at the pinned baseline. **Honored today** includes server-mediated behavior: a preference may affect the server's `DefaultAudioStreamIndex` or `DefaultSubtitleStreamIndex` without a dedicated local field read. **Gap** means missing local behavior/control; preserving JSON alone is not honoring it. Partial rows describe both. No port should implement a new policy accidentally and call it exact current parity.

| Server value / inventory entries | Status | Current behavior and required honest representation |
|---|---|---|
| `AudioLanguagePreference` | **honored today** | Editable; Jellyfin selects default stream indices and Pictureshow uses returned default audio index unless explicitly overridden. The earlier audit's “audio language not applied” claim was wrong because it searched only direct configuration reads. |
| `PlayDefaultAudioTrack` | **honored today through server default index; gap: no local control** | Returned default audio index carries server selection. Do not independently reorder by language ahead of it. No Pictureshow toggle or independent local policy. |
| `SubtitleLanguagePreference` | **honored today via server default index and Android caption-language override; gap: independent full policy** | Editable; caption override consulted when Android captions enabled. Preserve server index otherwise; do not infer missing server-mediated behavior from absence of a local read. |
| `SubtitleMode` | **honored today via server default subtitle index; gap: distinct client-side Always/OnlyForced/Smart policy** | Editable. None suppresses caption override; other modes share that override logic. Tests must distinguish server-returned default from local override. |
| `RememberAudioSelections` | **honored today when reflected in server default index; gap: local toggle/policy** | Track selection indices are reported to Jellyfin; server may return remembered default next time. No independent client memory map/configuration binding. Do not claim server-mediated memory universally verified. |
| `RememberSubtitleSelections` / audio-subtitle selection-memory inventory alias | **honored today when reflected in server default index; gap: local toggle/policy** | Same boundary; retain explicit Off and report index. No independently applied remember toggle. |
| `EnableNextEpisodeAutoPlay` | **gap** | App continuation is automatic except screen-reader/manual/cancel behavior; no server configuration binding. Do not claim this toggle is honored. |
| `DisplayMissingEpisodes` | **gap** | Preserved in configuration; no UI/query binding. |
| `GroupedFolders` / Group folders / Group library folders | **gap** | Preserved; no grouping configuration binding. |
| `DisplayCollectionsView` | **gap** | Preserved; no configured home-view filtering/binding. |
| `OrderedViews` / Ordered views / Library view order | **gap** | No explicit configuration binding; Home uses server view response order. No Pictureshow reorder UI. |
| `LatestItemsExcludes` / Latest items exclusions / per-library Include in Latest | **gap** | No explicit preference binding; Home requests latest for movie/show library responses. |
| `MyMediaExcludes` / My Media exclusions / per-library Include in My Media | **gap** | No explicit preference binding; Home uses returned libraries. |
| `HidePlayedInLatest` | **gap** | No Pictureshow filtering preference binding. |
| `EnableLocalPassword` | **gap** | Preserved; no local-password settings or gate. Ordinary server password sign-in exists. |
| `CastReceiverId` | **gap** | Preserved; no casting/receiver flow. |
| Server `DisplayPreferences`: ten home-section slots | **gap** | No DisplayPreferences client/storage binding; fixed row assembly. |
| Server `DisplayPreferences`: default landing screen | **gap** | Current account starts Home. |
| Server `DisplayPreferences`: per-library view type | **gap** | Fixed grid/shelves, no list preference. |
| Server `DisplayPreferences`: image type | **gap** | Automatic artwork fallback, no per-library image selection. |
| Server `DisplayPreferences`: image size | **gap** | Responsive platform layout, no stored library-size preference. |
| User profile display name/avatar/password editing | **gap** | Server identity is displayed, but only account selection/removal/sign-in exist; no editing. |
| Quick Connect enabled state | **honored today** | Login probes server capability and displays disabled/unavailable recovery; no enable-Quick-Connect setting in Pictureshow. |

“Through server default index” is a contract about consuming the returned value, not a claim every server version implements a given preference. The server is authoritative for default selection; a port reports any unsupported/missing default honestly and follows the exact fallback vectors.

## Other client-local inventory entries

These are not portable server preferences. This table keeps the rest of the audited inventory visible so absence is explicit.

| Inventory entry | Status in Android reference |
|---|---|
| Home-network quality, internet quality, Chromecast bitrate | **gap**: one generic bitrate cap only; no network classification or cast control. |
| Maximum video resolution; limit to supported resolution | **gap**: no user switch/cap; detected device limits in negotiation are honored automatically. |
| Audio channel count; VBR audio encoding | **gap**: no user selectors; negotiated capability channels only. |
| Cinema mode/pre-rolls; still-watching prompt; next-video overlay preference; external player | **gap**: no controls/flows; fixed next-episode card exists. |
| Skip-forward/back duration | **gap**: fixed seek behavior, no preference. |
| Enable DTS/TrueHD/Hi10p; prefer fMP4 HLS; software codecs/FFmpeg preference | **gap**: no manual toggles; real detected capabilities determine route. |
| Subtitle font family/text color/weight/outline color | **gap**: no app controls; System derives Android caption style when enabled. |
| Subtitle vertical position/offset | **gap**: no user preference; automatic safe-area positioning above overlays. |
| Theme; backdrop behavior; clock; watched indicator; series thumbnails | **gap**: no preferences; fixed dark presentation, artwork and badges. |
| Home Next Up age cutoff | **gap**: no cutoff preference. |
| Screensaver enable/timeout; screensaver age rating | **gap**: no in-app screensaver settings. |
| Refresh-rate switching; playback buffer length | **gap**: no viewer preferences. |
| Resume subtract duration; zoom/aspect | **gap**: no preferences; exact resume/fit behavior is fixed. |
| Night mode/audio behavior; bitstream format overrides; HDR overrides | **gap** beyond generic passthrough toggle and automatic capability detection. |
| Photo slideshow delay | **gap**: no photo player/timing preference. |
| Android TV home integration setting | **gap**: no preference, but Watch Next integration exists automatically (APP-10). |
| Browser hotkey/player behavior customization | **gap**: fixed TV controls and touch controls; no preferences. |
| Local account switcher/sign out | **honored today**, AUTH-07..12; local state, not UserConfiguration. |
| Max bitrate/passthrough/subtitle size/background/segment action inventory aliases | **honored today**, SET-01..05. |
| This TV; Supporter; About inventory aliases | Existing navigation/read-only diagnostics or purchase flow, not settings; SET-10 and information manifest. |

## Playback tuning extension (Android reference, leg 131)

This extension supersedes the local gaps above for the rows below. Defaults preserve detected device capabilities;
these are local viewer choices, not server account configuration. Android TV and touch expose the same values in
Network quality, Subtitle appearance, Subtitle conversion, and Continuation and output. They save immediately,
apply on the next viewing, and have bounded, scrollable controls. Renderer limits are stated beside the setting.

| ID | Setting | Acceptance |
|---|---|---|
| SET-13 | Network quality | Existing SET-01 becomes the Home cap (Auto/120/80/40/20/10/4 Mbps). Remote defaults to 20 Mbps; metered defaults to 4 Mbps, same choices. Metered wins over local classification. A resolved private/loopback/link-local server, including IPv6 unique-local fc00::/7, uses Home; unknown/public addresses use Remote, including on Wi-Fi. Auto means 200 Mbps. The PlaybackInfo bitrate and device profile both carry the effective cap. |
| SET-14 | Automatic quality and player override | Default On. Two substantial bandwidth samples below the current cap can reduce the cap to the largest allowed rung under 80% of measured bandwidth; below 4 Mbps uses the smaller budget. Debounce reductions for 30 s. Never auto-raise during a viewing. A one-tap Quality control cycles 4/10/20/40/120 Mbps then returns to network/automatic quality; the override suppresses adaptation and is scoped to that viewing. Reprepare preserves clock, pause and track intent. Neither network nor override changes another viewing's stored caps. |
| SET-15 | Subtitle appearance and preview | Live preview uses the same Media3 SubtitleView and cue transformation as playback, including an image sample. Size/background retain SET-03/04. Text color System/White/Yellow/Black/Green/Cyan; opacity 0–100%; edge System/None/Outline/Shadow with color; background color and opacity; position Authored/Bottom/Middle/Top; bold and italic. Image opacity is independent and never mutates the decoder's bitmap. Image coordinates remain authored, subject to PLAY-12 safe bounds. Defaults retain authored/system styling. Explicit Override authored ASS styling removes embedded text styling and sizes. Without it, authored ASS spans may win over local text colors. Burned-in pixels cannot be styled; preview explains that limit. |
| SET-16 | Subtitle burn-in | Automatic (default), Always, Only image formats. Automatic advertises supported local subtitle renderers. Always advertises Encode only for selected subtitles. Only image formats forces image subtitles to Encode while supported text remains local; unsupported text still converts when required. Off remains Off. The screen states video-conversion cost and that burned-in subtitles cannot use local styling/timing. Changing policy must change the actual PlaybackInfo profile, not merely a label. PLAY-27 remedy respects Always: select Automatic or turn subtitles off. |
| SET-17 | After credits | Default Keep scenes after credits waits for physical end before automatic next; final-window card may still offer manual Play now. Next episode during credits opts into PLAY-17 early countdown. Existing Outro Ask/Automatic/Off remains independent and skips only its marker; a scene after the marker must survive. In Keep scenes mode, reaching an outro or a remaining-time countdown cannot complete watched state early; automatic advance requires a real end-of-file event. |
| SET-18 | Rewind on resume | 0 (default), 5/10/15/30 seconds. Subtract once from positive saved position, clamp at zero. Never apply again on seek, route/quality changes, recovery, pause/resume or decoder replacement. From start stays zero; explicit Cast, remote-controller and deep-link handoff positions remain exact. |
| SET-19 | Audio output | Global SET-02 remains the ceiling. Per-format allow switches are permission only, intersected with the detected sink; all default allowed. Downmix to stereo (default Off) removes passthrough and caps decoded/requested channels at 2. Channel limit Detected/2/6/8 cannot exceed real decoder/output capability. Negotiation and local encoded/offload sink both honor disabled formats; never claim disabled passthrough succeeded. Audio-only conversion does not imply video re-encode. |
| SET-20 | Frame rate and dynamic range | Match frame rate default Off; Android's Media3 requests content rate when enabled. On TV, prefer a same-resolution Display.Mode at the lowest integer multiple of the received frame rate, preserving known HDR support; tolerate 1000/1001 timing. Restore the prior window preference on off, replacement, unknown rate or exit. Android/system/display may refuse; no success claim without measured output. Dynamic range follows supported video and platform metadata; this renderer has no manual public output-mode switch, so show a read-only explanation. |
| SET-21 | HDR and Dolby Vision overrides | Each Detected (default)/Force/Disable. Derived profile changes do not overwrite detected capability reports. Force is clearly an attempt that cannot add hardware and may fail. Disable rejects that range in negotiation, allowing server conversion. Prefer Dolby Vision over HDR10+ (default Off) removes HDR10+ advertising when Dolby Vision is available; actual hybrid-stream output remains decoder/platform dependent. No DV7 conversion is claimed. Conversion information/remedies explain viewer-disabled HDR honestly. |

Malformed stored JSON fields default independently, preserving accepted values in other fields; invalid whole JSON uses defaults.

Portable policy cases: `spec/vectors/playback-settings.json` and `spec/vectors/frame-rate-match.json`. Every platform owes these rules and request/profile
assertions; unsupported output controls must state the actual platform limitation. Vector success is not UI or
hardware-output evidence.
