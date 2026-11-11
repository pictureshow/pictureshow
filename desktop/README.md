# Desktop surface spike

MPL-2.0 Pictureshow code, dynamically loaded libmpv. This is a diagnostic app, not the desktop product.

With JDK 21 and a matching libmpv installed:

```
./gradlew :desktop:test :desktop:spikeRuntime
MPV_LIBRARY=/absolute/path/to/libmpv ./gradlew :desktop:run --args='render /path/to/generated.mkv evidence/render --auto'
MPV_LIBRARY=/absolute/path/to/libmpv ./gradlew :desktop:run --args='wid /path/to/generated.mkv evidence/wid --auto'
```

`desktop:vectorTest` executes core's existing compiled JVM vector suites with the desktop dependencies. Core source and tests remain unchanged. This preserves Kotlin friend-module access used by those suites.

`render` uses the libmpv OpenGL render API in a private JOGL context on macOS/Linux, or the D3D11 render API with the small native adapter on Windows, and reads RGBA8 pixels back, and draws the image in Compose/Skia. It is an honest SDR composition probe, with CPU copies and a 1280×720 ceiling, not a production performance or HDR solution. Render calls stay on the owning GPU thread, separate from player commands. JNA callbacks are retained through renderer teardown. It polls at 30 Hz in this spike; a product should use mpv frame callbacks and a GPU texture bridge.

`wid` passes a realized AWT Canvas native handle to mpv, leaving output/presentation with mpv. A deliberately overlapping Compose button tests native view stacking and hit testing. Interop blending is experimental; a successful surrounding control bar is not evidence that the overlapping button works.

Automation captures the actual app window as JPEG (800 px, quality 50), dumps mpv properties at each resize/fullscreen/subtitle stage, and uses Robot to click the overlapping Compose button. `sequence.txt` records whether the click reached Compose. All unattended audio is muted; `SPIKE_NULL_AUDIO=1` selects the null sink and cannot prove audible surround output. `audio-params` is decoded layout; `audio-out-params` is output layout.

Generate original CC0 media with `python desktop/tools/fixtures.py OUTPUT`: HEVC Main, Main10, AV1, silent AAC 5.1, SRT/ASS/PGS tracks. The PQ variant carries synthetic PQ metadata; it is not calibrated HDR content. Neither a 10-bit decoder nor PQ input proves HDR output. No media is fetched from any user's server.

The external libmpv used for development is not automatically a redistributable package. Shipping requires audited LGPL builds, complete matching sources/build recipes/notices, dynamic replacement, and an architecture-specific native dependency closure. Never bundle a development GPL build in an MPL-only distribution.

Windows CI builds `windows_d3d.cpp` against the headers in the pinned LGPL libmpv archive and sets `SPIKE_D3D_BRIDGE` to that DLL. D3D11 WARP is an explicit software device on hosted runners. `SKIKO_RENDER_API=SOFTWARE` avoids claiming a GPU-backed UI there. A failed or absent decoder/renderer fails the probe, while screenshots still require visual review.

macOS runs in a GUI login session. Fullscreen uses Apple's JDK desktop extension (`FullScreenUtilities` / `Application.requestToggleFullScreen`) so JDK marshals AppKit operations itself. Retina click coordinates are converted from Compose pixels to AWT points. Where global Robot input is unavailable, the probe records a separately named application-local AWT click; that does not claim macOS global-input permission.

Leg 128 adds production-shaped native surface candidates and separate geometry probes; see [NATIVE-SURFACES.md](NATIVE-SURFACES.md). The older readback and JDK fullscreen descriptions above document the original spike, whose surface gate failed.
