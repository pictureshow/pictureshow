# Qt + libmpv surface proof

Diagnostic prototype, MPL-2.0, Legitimate LLC. No product-stack acceptance is implied.

The QML surface uses Qt Quick `QQuickRhiItem`, RGBA16F, with real app controls and routed input above video. It does not use WebEngine. Qt's standard fullscreen request and an explicit immersive borderless implementation were tested; the latter saves/restores window flags and geometry and is checked against the actual display. The public code contains no accounts, remote machines, household media, or credentials.

## Frame path

- macOS: libmpv OpenGL render API → CGL RGBA16F IOSurface → shared Metal texture → GPU blit into Qt's RGBA16F item → Qt Metal scene / scRGB CAMetalLayer. `glFinish` orders producer completion; waiting for the preceding Metal command buffer protects the reverse dependency before reusing the IOSurface. There is no pixel readback or CPU bitmap upload in playback. This deliberately conservative single-surface synchronization can stall; a production ring of surfaces/fences needs separate benchmarking.
- Windows: libmpv D3D11 render API renders directly into the FP16 texture created by Qt on the same D3D11 device. Qt draws its controls and owns presentation. Hosted tests force WARP and cannot qualify hardware decoding or physical HDR output.
- Linux: libmpv OpenGL render API renders into an FBO attaching Qt's FP16 texture. External GL state is reset and QRhi invalidated around it. A host-owned X11/GL context does not require mpv's GPL X11 VO.

`QSG_RHI_HDR=scrgb` asks Qt for an HDR swapchain. The harness records the **actual** chain format and HDR info on the render thread, then copies an immutable JSON receipt to the GUI thread. On macOS it also reads the presenting CAMetalLayer and NSScreen EDR properties. HDR input metadata, a floating texture, or a separate API test alone is not proof of HDR presentation.

`hdr-api-state` is a separate, unpresented DXGI API-state probe. It creates both an FP16 scRGB and an RGB10A2 HDR10 composition chain, checks format/color-space support, sets color space and static HDR10 metadata (or explicit NONE for scRGB), and records HRESULTs. It **does not** inspect or qualify Qt's presenting swapchain. A real HDR-enabled Windows output is still needed to qualify Qt presentation.

## Build and run

Requires a shared open-source Qt6 SDK (6.7 or newer, tested against current SDKs), CMake, Ninja, and a dynamically linked LGPL libmpv0.42 with its audited LGPL dependency closure.

```sh
cmake -S desktop-qt -B qt-build -G Ninja -DCMAKE_PREFIX_PATH=/path/to/qt
cmake --build qt-build --parallel 2
ctest --test-dir qt-build/core --output-on-failure
python desktop-qt/tools/fixtures.py desktop-fixtures
python desktop-qt/tools/sustain.py desktop-fixtures
QSG_RHI_BACKEND=metal QSG_RHI_HDR=scrgb qt-build/qt-proof /path/to/libmpv desktop-fixtures evidence --sustain
```

Use `d3d11` on Windows and `opengl` on Linux. Without `QSG_RHI_HDR`, mpv targets SDR sRGB. With it, the diagnostic targets linear BT.709 with a1000-nit target; calibrated mastering/output accuracy is a separate test. All audio is muted and uses a null sink. Six-channel decode is not audible-surround or passthrough qualification.

The fixture generator is reused from the original desktop spike. `sustain.py` generates the same original CC0 mathematical signal directly at1920×1080/60Hz for135seconds, with silent AAC5.1; it is not household media. The sequence injects a local Qt mouse event through the window. That proves QML hit testing and event routing; OS accessibility permissions and a real mouse/keyboard remain separate qualification.

Snapshots read pixels only at named evidence stages, never per playback frame. Native display captures and geometry crops are preferred because Qt6.12's HDR `grabWindow` was observed to return corrupt JPEG input while the native display capture was readable. HDR screenshots are SDR representations and do not measure luminance. Native display capture requires OS permission; a fallback is explicitly labeled in receipts. Inspect video/subtitles and whole-display bounds: a completed sequence does not make black, damaged, or decorated video pass.

## Dependency licenses and reproduction

The application directly uses **Qt Core, GUI, QML, Quick, and Test**; transitive Qt modules are **Network, OpenGL, QMLIntegration**, and runtime QML support libraries from qtdeclarative. These modules permit **LGPL-3.0-only** (alternative GPL/commercial terms exist). GUI private QRhi headers belong to the same LGPL GUI module; their API compatibility is not promised. Test is harness-only. The sample does not use Qt WebEngine, Multimedia, Charts, Graphs, or commercial-only/GPL-only add-ons. Qt's SDK may contain unused tools/modules; that is not permission to redistribute their whole tree.

The four mpv public API headers are copied unmodified from upstream v0.42.0 and carry their ISC license. The app resolves the C ABI with QLibrary. mpv must be built `-Dgpl=false`; FFmpeg must be the explicit LGPL variant without GPL/nonfree components; libass is ISC and libplacebo LGPL-2.1-or-later. Dynamic linking and a configure flag do not replace a closure audit, matching corresponding source/build instructions, notices, replaceability, and applicable LGPL relink rights.

`tools/build-mpv.sh` builds a render-only LGPL library. On macOS, upstream currently gates direct VideoToolbox GL interop behind the native Cocoa VO. `tools/render-only-vt.patch` makes the render API's CGL interop independently available and links the OpenGL framework; it does not enable GPL output code. Apply it before Meson setup when qualifying direct VideoToolbox without native Swift/Cocoa glue. Full Cocoa/Swift build experiments are not a requirement of this embedding path.

Current primary sources checked for this proof:

- https://doc.qt.io/qt-6/qrhiswapchain.html
- https://doc.qt.io/qt-6/qrhiswapchainhdrinfo.html
- https://doc.qt.io/qt-6/qquickrhiitem.html
- https://doc.qt.io/qt-6/qtcore-index.html
- https://doc.qt.io/qt-6/qtgui-index.html
- https://doc.qt.io/qt-6/qtqml-index.html
- https://doc.qt.io/qt-6/qtquick-index.html
- https://github.com/mpv-player/mpv/tree/v0.42.0/include/mpv
- https://github.com/mpv-player/mpv/blob/v0.42.0/Copyright
- https://ffmpeg.org/legal.html

See `core/README.md` for the working C++ parity translation and real vector runner. This is a small translation sample, not a complete desktop core or Jellyfin direct-play integration.
