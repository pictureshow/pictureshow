# macOS numeric HDR gate

Build the actual runtime (`:desktop:spikeRuntime`) and native adapters using
`desktop/NATIVE-SURFACES.md`. The adapter requires the same LGPL libmpv dependency
closure as ordinary playback. Build the independent capture and optional virtual
endpoint in user space:

```sh
clang -O2 -fobjc-arc macos_capture.m -framework Cocoa \
  -framework ScreenCaptureKit -framework CoreMedia -framework CoreVideo -o macos-capture
clang -O2 -fobjc-arc macos_virtual.m -framework Cocoa -o macos-virtual
python3 fixture.py /absolute/path/to/fixtures
python3 macos_run.py --help
```

Example (all paths are explicit so existing builds can be reused):

```sh
python3 macos_run.py --java /path/to/java --runtime '/path/to/runtime/lib/*' \
  --layer /path/to/layer.dylib --window /path/to/window.dylib \
  --mpv /path/to/libmpv.dylib --capture /path/to/macos-capture \
  --fixtures /path/to/fixtures --output /path/to/new-evidence-directory \
  --gui-lock /path/to/shared-gui.lock --wait-gui-seconds 600 \
  --virtual-display /path/to/macos-virtual --capture-mode pq2020
```

Existing Screen Recording permission, macOS15+ and Apple Silicon are required.
The runner checks permission without resetting it or requesting a dialog. It
serializes with other GUI jobs before starting an app, refuses reused evidence
paths, and terminates/reaps only its own app and virtual-display processes.
No human judges pixels. Exit0 requires both actual drawable and independent OS
capture for all eight PQ and all eight SDR patches, actual captured headroom>1,
and live owned virtual-display ID equality when that option is selected.

`pq2020` uses SCStream's HDR configuration with10-bit full-range444 two-plane
YCbCr (`xf44`) and a BT.2100 PQ color space. Actual returned primaries, transfer
and matrix are checked; a request alone is insufficient. The decoder uses the
reported709 or2020 YCbCr matrix, MSB10 packing and the ST2084 inverse. Raw bytes,
plane strides, attachments and numerical medians are retained. Samples are
reported as linear BT.2020/203 units only as an interchange convention; the
analyzer multiplies by203 exactly, recovering absolute PQ signal nits. No fitted
exposure, gamma, reference-white or gamut normalization occurs.

Alternative `--capture-mode linear` tests custom linearBT2020 FP16 capture;
`preset` preserves Apple's FP16 ExtendedDisplayP3 screenshot preset; `pqp3`
preserves its P3/PQ streaming preset. `--stream` can also test streaming for an
FP16 mode. These alternatives are diagnostic comparisons: the measured FP16
paths clipped highlights, and unsigned P3/PQ cannot represent all BT.2020 primary
components. They must not replace the full-gamut strict gate merely to get green.
`macos_modern.m` is a separate macOS26 HDR-CGImage screenshot experiment; absence
of `hdrImage` is an explicit failure, not an SDR fallback.

The optional virtual helper uses private CGVirtualDisplay APIs, so an OS update
can invalidate it. Only the new finite1280x720 display is configured; physical
HDR state, mirroring, the main display and gamma tables are not changed. PQ
transferFunction1 plus reference mode advertises HDR; transferFunction0 is the
SDR control. Runtime receipts retain actual bits/headroom/HDR state. The helper
exits within120seconds even if its owner vanishes. It is a local test tool, not
an application dependency or an App Store API.

The app's linear light starts in mpv's203-nit units. On an HDR target the Mac presentation path
converts to100-nit EDR reference units; an SDR target keeps203-relative units so
SDR is not brightened or clipped by that conversion. Windows uses80-nit scRGB.
The drawable receipt declares the selected unit; OS PQ samples are decoded
independently. This is a fixed rendering contract, not a calibration of emitted
light. A reference virtual endpoint is used to provide enough headroom for the
1000-nit control. Physical display brightness and other display profiles still
need their own numeric qualification.

Apple's installed SDK documents that HDR ScreenCaptureKit modes have no effect
on Intel. A successful Apple-Silicon run cannot qualify an Intel compositor.
The physical Dell connection also needs actual HDR support/enabled receipts;
a monitor's model-level HDR advertisement alone is insufficient.

Primary references (checked2026-11-10):
[Apple HDR capture](https://developer.apple.com/videos/play/wwdc2024/10088/),
[EDR rendering](https://developer.apple.com/documentation/metal/performing-your-own-tone-mapping),
[optical output units](https://developer.apple.com/documentation/quartzcore/caedrmetadata/hdr10(displayinfo:contentinfo:opticaloutputscale:)),
[mpv reference-white discussion](https://github.com/mpv-player/mpv/issues/16899).

For an actual SDR-output negative control, add `--virtual-transfer 0
--expect-sdr-output`. This requires headroom<=1, rejects the PQ source as intact
HDR, and compares SDR against the explicit100/203 relative-white mapping. The
HDR positive gate never uses that SDR-only comparison contract. No scale is
computed from observed pixels.
