# Native surface repair experiments

This is an MPL-2.0 diagnostic harness, not a desktop release. Run core vectors with
`:desktop:vectorTest`; `:desktop:test` depends on that task. Use JDK 21 and dynamically
linked LGPL libmpv with matching architecture and public headers.

`layer MEDIA EVIDENCE [--sustain=125]` selects native presentation on macOS or Windows.
Set `MPV_LIBRARY` to the full libmpv path and `SPIKE_LAYER_BRIDGE` to the adapter.
`wayland MEDIA EVIDENCE [--sustain=125]` selects the new native Wayland/EGL route;
see `tools/linux-wayland/README.md` for the isolated compositor and build recipe.
`canvas MEDIA EVIDENCE --auto [--sustain=125]` retains the failed Linux GLX experiment.
`window METHOD EVIDENCE` isolates macOS window requests without mpv or JOGL.
The older `render` path still reads RGBA8 pixels; it is not this candidate's frame path.

## Presentation

macOS: libmpv's public OpenGL render API renders RGBA16F into an IOSurface. The same
storage is wrapped as a Metal texture and GPU-blitted into a CAMetalLayer drawable.
The layer requests extended linear ITU-R BT.2020 and extended dynamic range. A native
borderless child window sits below the transparent Compose window. Three FP16
IOSurfaces form a bounded producer/consumer pool. A flushed GL fence must signal
before Metal reads a slot, and its Metal command buffer must complete before GL
reuses it. The render thread polls these fences/statuses without synchronous GPU
completion waits in steady playback; resize and teardown explicitly drain the pool.
There is no per-frame CPU pixel transfer.

Rendering is update-driven. A bounded native condition wait is signaled by mpv
update callbacks; the Mac executor loops without a JVM timer between frames.
Callbacks only signal local state and are unregistered before context destruction.
The Mac probe requests 50 ms of audio-clock render headroom and passes each frame's
mpv target time to Metal timed presentation. For pinned mpv 0.42, source inspection
establishes nanosecond target times despite stale microsecond text in render.h;
the adapter subtracts mpv_get_time_ns and maps the interval to CACurrentMediaTime.
Past deadlines present immediately and are counted; implausible future deadlines
fail rather than retaining a drawable indefinitely. This clock contract must be
rechecked when upgrading mpv.

SPIKE_MAC_IMMEDIATE retains the ring without headroom/timed presentation;
SPIKE_MAC_SINGLE_SURFACE retains the synchronous comparison path. Receipts separate
render, fence latency, drawable acquisition, completion waits, target lead/late
counts and scheduler gaps. Submitted commands and submission-time report_swap do
not establish physical scanout timing. A user-interactive render-thread QoS request
is recorded. Total frame duration includes notification idle wait.
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
audio sink; this cannot qualify real GPU throughput, audible surround or physical HDR scanout.

Linux: the `wayland` mode owns an xdg-toplevel video surface and an SDR subsurface
above it. Compose ImageComposeScene draws the controls and performs pointer hit tests;
only changing UI pixels pass through wl_shm. A separate render thread owns EGL and
mpv rendering. The 10-bit EGL window is described as PQ/BT.2020 through
wp_color_manager_v1; the adapter requires advertised support and a ready image
description, then binds it to the video surface. The measured Weston 16 color-lcms
compositor accepts the committed surface without a protocol error. It may convert
that HDR surface to an SDR output, so this proves protocol-level source handling,
not connected HDR scanout. A native DRM render-node resource enables direct Intel
VAAPI import; no decoded video pixels are copied to the JVM.

The old GLX GLCanvas/popups remain as failed comparison paths. `egl_probe.cpp` is a
separate render-node isolation tool with one final readback, not an application frame
path. Diagnostic compositor screenshot capture is also separate from playback.

## Observed failures and interpretation

The original macOS ignored-fullscreen result reproduced in a window-only probe without
mpv or JOGL while the console was locked. After unlocking the same console, the same
Compose probe entered native fullscreen and restored its floating bounds. Packaging
alone did not repair the locked-session failure. The native-layer probe also measured
borderless window/video bounds equal to the screen and restored them. Screen recording
and Accessibility permissions belong to the automated screenshot/input harness.

The retained Linux GLX/popup path remains a FAILED surface experiment. `compose.layers.type=WINDOW` and Openbox
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

The new Wayland route passes the 125-second 1080p60 Main10 diagnostic with zero
VO or decoder drops on the measured Intel render node. A controlled legacy timer/
target-time configuration on that same EGL route also passes: scheduling alone does
not explain the old GLX failure. A separate controlled old-GLX experiment retained
the same CPU decode, fixture and Intel renderer: zero timing offset alone retained
2,514 VO drops, while disabling Mesa vblank synchronization alone produced zero.
This isolates the nested GLX/Xwayland/Weston vblank presentation chain as the old
limiter; the exact internal driver wait was not isolated. The chosen Wayland route
passes without that diagnostic environment override.

On 2026-11-10, the M2 Max passes the native 1080p60 Main10 counter gate with
50 ms render headroom: 22 VO drops over 125.552 seconds (0.292%), zero decoder
drops, real-time playback and no EOF. A second normal app launch passes with
55 drops over 125.485 seconds (0.731%). A direct-worker launch at the same recorded
process nice value fails with 109 drops over 125.997 seconds (1.442%); the host
subsequently reports load average 723 with concurrent GPU work. These observations
do not isolate load or launch context as the cause and do not establish a reliable
pacing margin under arbitrary contention. Its synchronous comparison also passes
(13 drops over 125.561 seconds, 0.173%); these results do not establish a speedup
from buffering. Earlier low-priority launches failed, so record the actual GUI
process priority and avoid attributing launch-context differences to the GPU path.
The prior Intel/Radeon result remains 33.54% VO loss under a 35–33% CPU speed
limit; an unthrottled Intel soak is still required. WARP also remains unqualified.

Zero application pixel readbacks is not enough if the decoder uses `vaapi-copy`,
which downloads GPU-decoded frames to CPU. The separately measured Linux `hwdec=no`
route has CPU decode plus native upload and must be described separately from direct
EGL VAAPI diagnostic samples. Do not promote any of these experiments as launch-ready.

## Evidence and limits

Each stage saves an 800-pixel JPEG, mpv properties and native geometry/API state.
Full-screen acceptance requires borderless actual window bounds equal to display bounds,
visible video and controls, then restoration. A placement flag or a green job is insufficient.
Global Robot, AppKit-queued and local AWT input fallbacks have distinct evidence names.
Only the first establishes system pointer routing; local dispatch establishes less.

The layer harness saves exact soak counter deltas in `soak-metrics.json`, a TSV
baseline at elapsed zero, and native timing snapshots without screenshot settling.
Its final GPU drain is checked after native renderer destruction and mpv teardown;
a late deferred Metal/device error must fail the sequence.

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

The earlier Intel macOS qualification build uses shared FFmpeg 8.0 with `--disable-gpl --disable-nonfree`,
libdav1d, VideoToolbox and normal x86 assembly; mpv v0.42.0 with `-Dgpl=false`, shared
libmpv, OpenGL/Cocoa/CoreAudio and VideoToolbox GL interop; and libplacebo v7.387.0
with its unused GPU backends disabled. libass, font libraries and dav1d must come from
LGPL/permissive builds. A minimal upstream Swift conditional fix is needed when
Vulkan is disabled: guard `view.swift`'s `MetalLayer` branch with `#if HAVE_VULKAN`.
The Apple silicon experiment uses shared FFmpeg 9.0.2 from an explicitly LGPL
build and a render-only mpv recipe in [tools/macos](tools/macos/README.md), with
OpenGL/VideoToolbox/CoreAudio and explicit framework linkage, without Cocoa VO or
Swift glue. Neither architecture's build should be replaced with a GPL development
libmpv/FFmpeg as evidence for this runtime.

A distributable release still needs complete matching source offers, build recipes,
notices, architecture-specific dependency closure, dynamic replacement and signatures.
No GPL Jellyfin Desktop implementation was used. Public contracts:
[mpv render API](https://github.com/mpv-player/mpv/blob/v0.42.0/include/mpv/render.h),
[OpenGL API](https://github.com/mpv-player/mpv/blob/v0.42.0/include/mpv/render_gl.h),
[D3D11 API](https://github.com/mpv-player/mpv/blob/v0.42.0/include/mpv/render_d3d11.h),
[FFmpeg licensing](https://ffmpeg.org/legal.html).

## Numeric HDR output gate (leg136)

The generated CC0 source and real-app/independent-output gates are described in
[tools/hdr/README.md](tools/hdr/README.md). These replace the old retagged SDR
fixture as color evidence. Free hosted Windows VDD+WGC and local Apple-Silicon
reference-virtual-display+ScreenCaptureKit routes have numeric PQ/SDR output
checks. Current Linux output results remain in the leg136 execution report;
accepted surface tags or app-only readbacks are insufficient.

The renderer preserves the full PQ10000-nit domain when source mastering data
is missing. Windows converts linear BT2020 to extended BT709 scRGB after mpv
gamut handling; Mac uses an explicit203-to100 unit conversion on an HDR target
and retains203-relative units on an SDR target. The selected startup display
is tested; moving between output profiles during playback is not qualified by
these tests. Normal playback has no recurring CPU frame readbacks.
