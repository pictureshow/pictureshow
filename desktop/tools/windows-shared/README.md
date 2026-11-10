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
