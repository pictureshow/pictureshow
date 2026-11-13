# Native mpv control for the Mac pacing experiment

Use the pinned official mpv v0.42.0 archive and architecture-matched shared LGPL
FFmpeg/libass/libplacebo dependencies. Build both CLI and library together with:

```sh
meson setup build mpv-0.42.0 --prefix="$prefix" \
  --auto-features=disabled --buildtype=release -Dgpl=false -Dlibmpv=true \
  -Dcplayer=true -Ddefault_library=shared -Dcocoa=enabled -Dswift-build=enabled \
  -Dmacos-cocoa-cb=enabled -Dgl=enabled -Dgl-cocoa=enabled -Dplain-gl=enabled \
  -Dvideotoolbox-gl=enabled -Dcoreaudio=enabled -Dvulkan=disabled \
  -Dmacos-10-15-4-features=enabled -Dmacos-11-features=enabled \
  -Dmacos-11-3-features=enabled -Dmacos-12-features=enabled
meson compile -C build -j2
meson install -C build
```

A build-only source correction is needed in that tag when Vulkan is disabled:
wrap `View.isOpaque`'s `if let metalLayer = layer as? MetalLayer` block in
`#if HAVE_VULKAN` / `#endif` in `video/out/mac/view.swift`. OpenGL keeps the
unchanged `return true` path. This guard does not change playback scheduling.
Do not use the separate render-only build script to claim a native CLI control.

In this configuration upstream native CocoaCB/CAOpenGLLayer is selected using
`--vo=libmpv`. It owns its window, layer and CVDisplayLink; it does not use the
Pictureshow adapter. The CLI embeds mpv core objects, whereas the app loads the
matching shared libmpv. State this distinction and record build provenance,
actual mapped dependency paths/hashes, native VO logs, decoder and output state.
A matching version string alone does not prove matching binaries.

Reject native startup trials with zero drawable dimensions or no actual window,
even when audio/media time advances. One macOS27 trial hung in nested
`GLLayer.display` → `CATransaction.flush` → `GLLayer.display` → mutex wait;
the pinned implementation holds an NSLock around flush. The stack is consistent
with a reentrant lock hang, not proof of GPU saturation. Preserve the stack and
failed counters, keep the plain native renderer unchanged, and clean up that
owned process if graceful quit cannot finish. Such a trial is not a valid
visible-playback control.

Launch native mpv with no external config, VideoToolbox, muted CoreAudio,
`--video-sync=audio`, borderless non-Space fullscreen (`--native-fs=no`), the same
fixture and physical screen. Use IPC to measure monotonic elapsed time, media
advance, VO/decoder counter deltas and explicit false EOF for at least125seconds.
Interleave app and control serially; include both windowed and fullscreen runs.
Verify actual geometry/display/refresh and nice0 rather than relying on flags.
Cocoa explicit geometry is backing-pixel based on this version; measure window
bounds after launch instead of assuming logical dimensions.

The plain native control retains its native default output format and color
management. Its bandwidth/compositor path differs from the app's FP16 EDR layer;
a clean control localizes a difference to the complete app integration, not any
one function. For a coordinated visibility-controlled experiment, set `--ontop=yes`
in the native arm and `SPIKE_MAC_TEST_ONTOP=1` in the app arm. Preserve deliberate
cover/uncover trials separately from accepted visible-playback runs.

Verify the actual FFmpeg license/configuration and mpv gpl=false. The measured
closure reports FFmpeg LGPL version3 or later. Binary distribution still needs
matching source, notices and the appropriate replacement/signing obligations.
