# Mac render-only LGPL libmpv

`build-mpv.sh PREFIX SCRATCH` builds mpv 0.42.0 with its public CGL render API,
VideoToolbox interop and CoreAudio, without its native Cocoa VO or Swift glue.
Use architecture-matched, shared LGPL FFmpeg dependencies. Prefer paths without
spaces: some dependency pkg-config files fail to escape their embedded prefixes.
The application adapter remains MPL-2.0; the small Meson patch changes the LGPL
mpv build configuration, with upstream source and licensing retained.

The patch explicitly links CoreVideo and IOSurface and loads the OpenGL framework independently of the native Cocoa VO and
compiles the CoreFoundation string utility required by CoreAudio. Merely enabling
VideoToolbox GL in a Cocoa-disabled build leaves that CoreAudio symbol unresolved
on Darwin; audio-device initialization then crashes. This recipe retains muted
CoreAudio during the playback probe rather than replacing device output with a
null sink. Surround speaker mapping and listening remain separate checks.

Compile `desktop/src/native/macos_surface.mm` as an Objective-C++ ARC dynamic
library against the installed mpv headers/library and Cocoa, OpenGL, Metal,
QuartzCore and IOSurface frameworks. Give the adapter an rpath to `PREFIX/lib`.
Use application-relative rpaths for packaging. For test runs, stage the dependency
closure and fixtures in a location accessible to the GUI launch context. A library
load or media-file open that stalls before playback is a setup failure, not a
pacing result. Record actual process priority: a GUI launched from a lower-priority
worker can inherit its nice value. On macOS, `renice 0 -p PID` selects absolute
priority 0; `renice -n 0` means add zero. Normalize only the owned test process,
verify the result, and keep the comparison context consistent. Avoid injecting a broad dependency
folder with `DYLD_LIBRARY_PATH`: replacement image libraries can interfere with
AppKit before playback starts. A named `jpackage` app image makes foreground
activation and app identity observable in the native test harness.

`SPIKE_MAC_SINGLE_SURFACE=1` retains the synchronous comparison path; the default
uses three FP16 IOSurfaces. A slot is reused only after its GL producer fence and
Metal consumer command buffer complete. The render thread polls GL fences without
waiting, then submits the Metal blit. It never assumes that `glFlush` completes
writes or that Metal can wait directly on a `GLsync`. Resize and final teardown
explicitly drain the bounded pool before freeing it. Diagnostic receipts count
rendered/submitted frames and retired targets; neither command submission nor
mpv's `report_swap` proves physical display presentation.

The default requests 50 ms of render headroom and schedules each completed frame
with Metal's `presentDrawable:atTime:`. `SPIKE_MAC_IMMEDIATE=1` retains immediate
presentation with zero headroom. For pinned mpv 0.42, `vo_libmpv.c` assigns the
nanosecond `vo_frame::pts` unchanged to `NEXT_FRAME_INFO.target_time`, although
`render.h` describes microseconds. The adapter uses `mpv_get_time_ns`, then maps the
remaining interval to `CACurrentMediaTime()` seconds. Recheck the actual source
when upgrading mpv. Late deadlines present immediately and increment a counter;
future deadlines over one second fail as a clock/unit mismatch.

The current public mpv render API exposes OpenGL, D3D11 and software backends, not
a caller-owned Vulkan or Metal target. `vo=gpu-next`/MoltenVK is a different output
integration, not a switch for this render context. Recheck current upstream headers
before adopting downstream graphics ABIs. Measured HDR API state still needs a
physical HDR output and colorimetry validation before release.

The native 1080p60 Mac sustain harness records the end metrics and screenshot,
then requires strict VO drops below 1% and zero additional decoder drops. It
always attempts native drain and mpv teardown on failure. Check the failure file,
metrics and teardown receipt as well as process status: `open -W` alone does not
propagate an application's playback gate result. A passing counter gate remains
separate from physical presentation and HDR colorimetry qualification.
