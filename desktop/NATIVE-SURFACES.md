# Native surface repair experiments

This is an MPL-2.0 diagnostic harness, not a desktop release. Run core vectors with
`:desktop:vectorTest`; `:desktop:test` depends on that task. Use JDK 21 and dynamically
linked LGPL libmpv with matching architecture and public headers.

`layer MEDIA EVIDENCE [--sustain=125]` selects native presentation on macOS or Windows.
Set `MPV_LIBRARY` to the full libmpv path and `SPIKE_LAYER_BRIDGE` to the adapter.
`canvas MEDIA EVIDENCE --auto [--sustain=125]` selects the Linux GLCanvas experiment.
`window METHOD EVIDENCE` isolates macOS window requests without mpv or JOGL.
The older `render` path still reads RGBA8 pixels; it is not this candidate's frame path.

## Presentation

macOS: libmpv's public OpenGL render API renders RGBA16F into an IOSurface. The same
storage is wrapped as a Metal texture and GPU-blitted into a CAMetalLayer drawable.
The layer requests extended linear ITU-R BT.2020 and extended dynamic range. A native
borderless child window sits below the transparent Compose window. GPU completion
waits establish cross-API ownership; there is no per-frame CPU pixel transfer.
This synchronous bridge is a qualification candidate, not a finished frame scheduler.
Window bounds, layer state and the presenting NSScreen's actual/potential EDR limits
are recorded. A float format and EDR request do not prove physical HDR brightness.
The prototype resolves its own NSWindow by exact title; production needs explicit
window registration, multi-monitor policy and color management validation.

Windows: libmpv's public D3D11 render API draws directly into an FP16 flip swap-chain
backbuffer. The API selects linear scRGB (`RGB_FULL_G10_NONE_P709`). HDR10 static
metadata is explicitly cleared: scRGB uses linear values, not a PQ HDR10 signal.
An OUTPUT shader converts mpv's 203-nit linear reference to scRGB's 80-nit reference.
A separate native HWND is kept behind the transparent Compose control window.
`ps_sync` uses asynchronous positioning so the EDT cannot deadlock waiting for the
render thread to enter its message loop. Hosted CI explicitly uses WARP and a null
audio sink; this cannot qualify real GPU throughput, audible surround or HDR output.

Linux: an application-owned GLX GLCanvas presents libmpv without readback. Compose
controls occupy two popup windows above the heavyweight canvas. This requires window
manager, focus, accessibility and multi-monitor hardening. GLX does not establish
direct Intel VAAPI interop; that requires EGL plus the native display resource.
`egl_probe.cpp` is a separate render-node isolation tool. It performs ONE final image
readback for comparison, so it is diagnostic evidence, not the application's frame path.
Xvfb is software X11 with no HDR compositor. A render-node result is not display output.

## Observed failures and interpretation

The original macOS ignored-fullscreen result reproduced in a window-only probe without
mpv or JOGL while the console was locked. After unlocking the same console, the same
Compose probe entered native fullscreen and restored its floating bounds. Packaging
alone did not repair the locked-session failure. The native-layer probe also measured
borderless window/video bounds equal to the screen and restored them. Screen recording
and Accessibility permissions belong to the automated screenshot/input harness.

Linux remains a FAILED surface experiment. `compose.layers.type=WINDOW` and Openbox
made popup controls initially visible and clickable, but controls became black after
resize. Recreating popup peers made labels visible, yet a real click after fullscreen
triggered recomposition and reverted the owner to floating bounds. Updating Compose's
placement state did not resolve it. The last two Linux popup/placement commits are
retained diagnostic attempts, not verified repairs. The earlier stable geometry baseline
also failed its visual control gate. Direct EGL samples rendered the codecs correctly on
Intel; llvmpipe remained inconsistent. Neither result establishes Linux HDR output.

The new separate-DLL Windows runtime shows video and both control layers in all five
fullscreen captures. Whole-frame inspection initially missed bottom strips; exact pixel
crops and independent review confirmed them. However, the BtbN FFmpeg shared avfilter
build incorporates static libass/libplacebo filter dependencies; separate direct mpv
DLL imports do not establish a fully dynamic transitive closure. This remains open.

Measured two-minute playback retains material frame loss on the tested Mac and WARP
runner. Zero application pixel readbacks is not enough if the decoder uses `vaapi-copy`,
which downloads GPU-decoded frames to CPU. The separately measured Linux `hwdec=no`
route has CPU decode plus native upload and must be described separately from direct
EGL VAAPI diagnostic samples. Do not promote any of these experiments as launch-ready.

## Evidence and limits

Each stage saves an 800-pixel JPEG, mpv properties and native geometry/API state.
Full-screen acceptance requires borderless actual window bounds equal to display bounds,
visible video and controls, then restoration. A placement flag or a green job is insufficient.
Global Robot, AppKit-queued and local AWT input fallbacks have distinct evidence names.
Only the first establishes system pointer routing; local dispatch establishes less.

The sustained fixture is generated CC0 1920x1080 Main10 at 60fps with continuous
135-second timestamps. The probe runs at least 125 seconds without looping and records
media-clock progress, VO/decoder drops and presentation calls. Native submittedFrames
counts swap/present calls and may include redraws; it is not a count of unique video frames.
All unattended audio is muted. Six decoded AAC channels do not prove speaker mapping.
PQ test metadata on an SDR test pattern is not a calibrated HDR reference.

## LGPL dependency builds

The original Windows workflow pins the official mpv v0.42.0 LGPL archive. That DLL
statically incorporates its media dependencies and does not meet this experiment's
stricter separate-DLL requirement. The separate-source workflow builds mpv with shared
FFmpeg/libass/libplacebo and audits the recursive PE import closure and actual FFmpeg
runtime license/configuration. Its observed results, recipes and compiler-runtime
exception notices must be checked with the experiment report. Fixture-generation FFmpeg
is a tool, excluded from the playback runtime PATH. Workflows have cancellation, time
limits and finite artifact retention.

The macOS qualification build uses shared FFmpeg 8.0 with `--disable-gpl --disable-nonfree`,
libdav1d, VideoToolbox and normal x86 assembly; mpv v0.42.0 with `-Dgpl=false`, shared
libmpv, OpenGL/Cocoa/CoreAudio and VideoToolbox GL interop; and libplacebo v7.387.0
with its unused GPU backends disabled. libass, font libraries and dav1d must come from
LGPL/permissive builds. A minimal upstream Swift conditional fix is needed when
Vulkan is disabled: guard `view.swift`'s `MetalLayer` branch with `#if HAVE_VULKAN`.
Do not reuse a GPL development libmpv/FFmpeg as evidence for this runtime.

A distributable release still needs complete matching source offers, build recipes,
notices, architecture-specific dependency closure, dynamic replacement and signatures.
No GPL Jellyfin Desktop implementation was used. Public contracts:
[mpv render API](https://github.com/mpv-player/mpv/blob/v0.42.0/include/mpv/render.h),
[OpenGL API](https://github.com/mpv-player/mpv/blob/v0.42.0/include/mpv/render_gl.h),
[D3D11 API](https://github.com/mpv-player/mpv/blob/v0.42.0/include/mpv/render_d3d11.h),
[FFmpeg licensing](https://ffmpeg.org/legal.html).
