# Windows HDR numeric verification

Run the `Desktop numeric HDR Windows` workflow on an authorized test branch.
It installs a signed virtual display driver only on the disposable hosted runner,
tries enabling HDR, then plays generated PQ and SDR fixtures through the actual
Compose/libmpv native video window. A separate process captures both the window
and monitor using Windows.Graphics.Capture RGBA16Float. No screenshot/JPEG is used
for the numeric verdict. One optional app swapchain readback identifies which
boundary first changed the signal; it never substitutes for a display capture.

The strict result requires the actual app monitor to report HDR output and all
PQ+SDR bars to match at the display-capture boundary. A red result is retained
as a useful missing-capability/failure receipt, not converted to a skipped PASS.
There is no physical HDMI/infoframe or calibrated panel-luminance claim.

For an authorized real-GPU Windows beta machine, prepare the same `native/`
LGPL runtime, Java classpath and capture/adapter binaries using the workflow's
build steps, then run:

```powershell
python desktop/tools/hdr/fixture.py desktop-fixtures/hdr
& desktop/build/hdr-capture.exe --enable-hdr > desktop-evidence/display-after.jsonl
& desktop/tools/hdr/windows_run.ps1 -HardwareGpu
```

The beta run uses hardware D3D rather than WARP. It does not install VDD. Enable
HDR on the intended output before running; the test fails closed if the actual
app lands on another SDR monitor. Keep a single intended test display active, or
place the app on it before capture. The host needs an unlocked interactive
Windows desktop, capture support, the dependencies above and an HDR output.
The script exits the app normally, checks teardown, and stops only its own PID
on failure. Generated patterns are CC0; results can be reviewed without asking a
person to judge a screen.
