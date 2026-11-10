# Numeric HDR verification

The original CC0 test signal has eight vertical patches: black, gray100, gray203,
gray1000, BT.2020 red/green/blue100, and gray50. Values name linear channel nits;
colored patch luminance differs from its active channel value. Source limited-range
10-bit YUV is computed from ST2084/BT.2020 NCL, encoded losslessly, and decoded
byte-for-byte against the generated raw frame. The manifest preserves the actual
quantized values (gray1000 is approximately1004.1919 signal nits). The separate SDR
fixture is BT.709/BT.1886 with declared203-nit reference white; gray1000 becomes
white203 in that fixture. No borrowed media or metadata-only HDR retagging.

Generate with `python desktop/tools/hdr/fixture.py OUTPUT` (ffmpeg/libx265 required).
Run analyzer controls with
`python -m unittest discover -s desktop/tools/hdr -p test_analyze.py`.

`analyze.py MANIFEST pq|sdr CAPTURE.json` compares all eight captured channel medians
against source with6% relative/3-nit absolute tolerance, and demands HDR gray1000
above twice SDR reference white. No fitted exposure, white balance, gamut or gamma
normalization is allowed. Linear scRGB reference white is80nits, linear EDR uses203;
BT.2020→BT.709 comparison preserves negative scRGB channels. Clipped SDR, wrong
white scale and wrong primary conversion are deliberate rejection controls.

## Evidence boundaries

1. Decoder source metadata plus exact generated-source decode.
2. Actual app presentation drawable/swapchain pixels, captured opt-in once.
3. Independent OS/compositor capture of that app's pixels.
4. Active HDR desktop/output state on the exact display being captured.
5. Physical HDMI signal/monitor luminance and gamut (not established by software).

A numeric PASS at one boundary does not establish later boundaries. FP16 buffers,
accepted PQ descriptions,10-bit modes and EDR flags alone do not establish pixels.
An SDR desktop can expose HDR values to an OS capture before display adaptation;
report that useful capture result separately from active HDR output qualification.
Signal nits are decoded color values, not photometer measurements of the panel.

Windows instructions: [windows.md](windows.md). Linux instructions:
[linux.md](linux.md). Mac: build `:desktop:spikeRuntime` and native adapters from
`desktop/NATIVE-SURFACES.md`, compile `macos_capture.m` per its runner docstring,
then run `macos_run.py --help` for explicit paths and a shared GUI lock. HDR SCK
requires macOS15+ on Apple Silicon and existing Screen Recording permission;
the script does not reset permissions or request dialogs. Existing Intel SCK
implementations ignore HDR mode, so that path cannot qualify Intel compositor HDR.

The scripts are diagnostic gates, not product UI or a physical display calibration
system. CI uses only generated public signals. Local raw captures contain only
the test app filter; preserve raw floats/ICC metadata for numeric audit. No AI
image interpretation is required. There are no purchases, recurring jobs, cloud
GPU rentals or installations on a tester's gaming PC in this harness.
