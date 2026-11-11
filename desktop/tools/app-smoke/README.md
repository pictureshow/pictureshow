# Desktop application smoke

`python desktop/tools/app-smoke/run.py --work PRIVATE_FRESH_DIRECTORY --evidence FRESH_EVIDENCE_DIRECTORY`

The harness generates original CC0 H.264/AAC media, two audio languages, subtitles,
posters, two movies and two episodes. `Signal One` is the 180-second direct-play
and resume signal. `Archive Signal` is a 20-second FFV1 movie, deliberately absent
from the desktop device profile, so Jellyfin must convert it to H.264. The harness
starts its own Jellyfin 10.11.11, creates an
isolated viewer, seeds a 12-second resume point and launches the actual desktop
runtime. Linux uses the pinned Jellyfin container; Windows and macOS use pinned
official portable archives. The server and app have finite lifetimes and cleanup
runs on failure too. No existing server can be selected. Do not upload the private
work directory: it contains ephemeral credentials, server state and raw logs.

The app entry point is:

```
MainKt app-smoke SERVER USER PASSWORD EVIDENCE
```

The app writes `quick-connect-request.json` with a newly issued `code`; the harness
authorizes it as the fixture viewer and writes `quick-connect-approved.json`.
The app must render and exercise its real screens and produce
`app-smoke-result.json` with `pass: true` and named Boolean checks:

```
server, passwordSignIn, home, library, sort, detail, seasons, episodes,
resume, playback, progress, audio, subtitles, nextEpisode, settings, quickConnect,
conversion, autoplayNext
```

The outer verifier also polls Jellyfin sessions independently, requiring active
playback and an advancing reported position for the same item. An initial Start
at a nonzero resume position does not count as progress. The server must observe
Archive Signal as a transcoding session and an FFmpeg process in only the owned
server process tree/container. At exit, both active playback and owned encoders
must be gone. To corroborate natural continuation, the server must observe
episode one at or after 170 seconds followed by episode two; the app separately
checks native EOF and automatic transition. Seek to 168 seconds and wait for EOF
so the ten-second reporting cadence has time to publish the near-end position.
Missing checks, failed app exit, stale output directories, absent Quick Connect
approval and static server progress all fail. Run verdict negative controls with:

```
python -m unittest discover -s desktop/tools/app-smoke -p test_smoke.py -v
```

`build-native.sh` creates development smoke dependencies using LGPL-disabled-GPL
FFmpeg and mpv plus the qualified native adapter. FFmpeg includes its built-in
decoders/demuxers/parsers and libdav1d for software AV1. The isolated Mac/Linux
smoke dependency build then explicitly omits the FFV1 decoder, with a separate
receipt, so Archive Signal tests a genuinely unavailable decoder. This omission
is a test-closure choice and must not restrict the shipping device profile.
The pinned Windows upstream archive retains its own decoder set; a real requested
bitrate cap can require conversion there even when FFV1 decoding is available.
HTTPS uses Secure Transport on Mac and GnuTLS on Linux. Linux retains stock Weston 16
and uses `softpipe`; the AWT shell has its own Xvfb display. Windows uses the
pinned upstream LGPL mpv archive and WARP on hosted CI. These software runs do not
qualify physical HDR, speaker output, real-GPU performance or release packaging.
The separate numeric HDR and pacing gates retain their existing entry points.

The GitHub `Desktop app` workflow runs the JVM/vector gate and application smoke
on free standard Linux, macOS and Windows hosted runners, with a separate Ubuntu
Android `check assembleDebug` gate. Evidence includes the named app checks,
independent server samples, library fixture provenance, generated-media license,
native build hashes and test XML. Credentials and raw server/app logs are outside
the artifact tree; only selected sanitized app errors/progress are published.

Sources checked 2026-11-10: official [portable installation instructions](https://jellyfin.org/docs/general/installation/advanced/manual/),
official [container instructions](https://jellyfin.org/docs/general/installation/container/),
and versioned [macOS archives](https://repo.jellyfin.org/files/server/macos/stable/v10.11.11/) /
[Windows archives](https://repo.jellyfin.org/files/server/windows/stable/v10.11.11/).
Archive digests were computed from those exact downloads on that date; they are
not claimed to be upstream signature verification. Docker digest matches the
existing repository launch-fixture capture. Ad-hoc Mac server signing follows the
official portable instructions and applies only to the disposable downloaded test
server. Signing the Pictureshow application remains a separate packaging task.
