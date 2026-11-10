# Separate Windows LGPL runtime experiment

This workflow builds mpv v0.42.0 from its source tag with `gpl=false`,
`default_library=shared` and `prefer_static=false`. It links BtbN's pinned FFmpeg
8.1 LGPL-shared archive plus MSYS2 UCRT64 shared libass and libplacebo. The FFmpeg
archive and mpv source digest are checked before use. MSYS package versions and
package licenses are retained with each CI artifact; these dependencies currently
track the MSYS repository and are not yet a reproducible release lockfile.

Run `.github/workflows/desktop-compose-shared.yml` on an authorized public test
branch. `build.sh` requires the UCRT64 packages named in that workflow. It never
installs the MSYS FFmpeg package, which uses a different license configuration.

`closure.py` follows actual PE imports recursively from libmpv and mpv.exe. It
collects only resolved non-system DLLs, requires direct separate imports of libass,
libplacebo and all six FFmpeg libraries, and retains hashes and package licenses.
The GCC standard library/runtime DLLs may use the GCC Runtime Library Exception;
those notices are retained separately from the LGPL media-library configuration.
`runtime-audit.py` loads each FFmpeg DLL and asserts its actual reported license is
LGPL, its configuration enables shared libraries, and GPL/nonfree are not enabled.
mpv's `--version`, Meson configuration and dependency paths are retained as evidence.

The fixture generator's Chocolatey FFmpeg is a separate tool. It is never copied
into the runtime directory. The LayerProbe PATH contains only the audited runtime,
Windows system DLLs and Java, preventing that generator from supplying runtime DLLs.
Only generated CC0 fixtures are uploaded; no server or household media is used.

The probe runs HEVC Main, HEVC Main10, AV1, PQ-metadata and a 125-second 1080p60
Main10 test, plus the desktop vectors. Inspect the retained JPEGs before accepting
video, subtitle or fullscreen behavior. The hosted runner uses D3D11 WARP and null
audio. A successful job does not establish real GPU throughput, physical HDR output,
or audible multichannel audio. Runtime artifacts remain diagnostic: release work
still needs complete source correspondence, notices, reproducible dependency pins,
dynamic replacement checks, packaging and signing.

## Observed qualification result, 2026-11-10

[CI run 42885965595](https://github.com/pictureshow/pictureshow/actions/runs/42885965595)
at `b2fd5baa112d0ad3bfc8bf2262924b7e53838827` completed successfully.
The audit retained 34 PE files and confirmed direct libmpv imports of `libass-9.dll`,
`libplacebo-387.dll`, `avcodec-62.dll`, `avfilter-11.dll`, `avformat-62.dll`,
`avutil-60.dll`, `swresample-6.dll` and `swscale-9.dll`. The six FFmpeg libraries
reported LGPL version 3 or later; mpv reported `gpl=false`, shared libraries and
`prefer_static=false`. Thirty vector test methods passed with no failures or skips.

This is a **partial surface failure**, despite the green job. The actual Main and
Main10 fullscreen JPEGs show video and both Compose controls. The AV1 fullscreen
JPEG has neither control area visible; PQ and 1080p60 fullscreen JPEGs retain the
top badge but lose the bottom controls. Their layout receipts all say ready=true,
which therefore does not establish visible controls. The cause is unresolved.
Restored SRT, ASS and PGS screenshots show subtitles above the control bar, and all
five cases retain clean teardown receipts.

The sustained run lasted 125.9894291 seconds. Media time advanced 0.633 to 127.458;
VO drops increased 34 to 6995 (+6961), with decoder drops zero. This WARP result
fails smooth 60 fps qualification. FP16/scRGB color-space and metadata-clear calls
returned S_OK on an SDR hosted output; physical HDR output remains unqualified.

This also **does not eliminate every transitive static copy**. BtbN's reported
FFmpeg configuration includes `--pkg-config-flags=--static`, `--enable-libass` and
`--enable-libplacebo`; its avfilter PE imports contain no external libass or
libplacebo DLL. The source-built libmpv uses the separate DLLs above, but FFmpeg's
filters incorporate static copies internally. A strict requirement that every
occurrence of those libraries be dynamic remains open. A future qualification
build must use an appropriate source-built FFmpeg without those static copies.

Matching source/build-recipe and notice provenance is in [SOURCES.md](SOURCES.md).
The current build proves the direct DLL boundary, not complete release packaging.
