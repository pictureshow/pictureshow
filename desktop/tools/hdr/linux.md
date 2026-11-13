# Linux compositor numeric HDR gate

`desktop-hdr-linux.yml` builds stock Weston 16.0.2 and a dynamically linked LGPL
mpv 0.41/FFmpeg 8 stack on a disposable Ubuntu 24.04 runner. It uses Mesa softpipe and stock Weston's FP16 shadow-buffer composition path,
preserves the full PQ domain (`target-peak=10000`) for the compositor to adapt,
plays the generated CC0 PQ and SDR HEVC fixtures through Pictureshow's actual
Compose/Wayland app, and captures Weston's composited framebuffer. The numeric-only mode loops the static fixture and warms up multiple cycles before pausing, so initial software shader compilation cannot consume the entire two-frame file. An isolated diagnostic module also
observes `output.frame_signal`, emitted by stock Weston's GL renderer after it
paints the final output FBO. That observer samples the bound output FBO
synchronously and records its handle, viewport and GL error state. It changes no
compositor color or presentation code. When present, that independent output
readback is the gate's pixel boundary; the protocol/PBO screenshot is retained
for comparison. The default gate leaves all app video readbacks disabled. Set `SPIKE_HDR_TRACE_APP=1` to retain auxiliary per-frame default-EGL FRONT/BACK RGB/RGBA, alpha, buffer and time/EOF diagnostics. These samples never
substitute for a compositor output measurement. Small diagnostic reads explicitly set and restore GL packing state and read bindings.

`GALLIUM_DRIVER=softpipe` selects an independent software implementation. For the isolated compositor only, `MESA_EXTENSION_OVERRIDE=-GL_EXT_shader_framebuffer_fetch_non_coherent` selects stock Weston's existing FP16 shadow path instead of in-shader framebuffer fetch. The color core is unmodified. The gate requires actual softpipe/FP16-shadow receipts from each compositor and softpipe receipts from each app. This choice follows observed failures with the hosted llvmpipe/in-shader route; it does not qualify that driver path.

The gate compares eight patch medians, including a 1000-nit gray, against the
source's quantized expected linear values without fitted normalization. It
requires actual output PQ/BT.2020 receipts, checks SDR content, and rejects the
same HDR source captured through an actual SDR compositor output. The ordinary
SDR output's declared 80-nit reference is adapted from the source's declared
203-nit reference for its relative-appearance control.

Run the workflow on a public repository with standard hosted runners, or install
the prerequisites listed in that workflow into a disposable Ubuntu 24.04
environment and run:

```sh
bash desktop/tools/hdr/linux_build.sh
./gradlew --no-daemon :desktop:spikeRuntime
python3 desktop/tools/hdr/fixture.py desktop-fixtures/hdr
bash desktop/tools/hdr/linux_run.sh
```

Inspect `desktop-evidence/hdr-linux/gate.json` (including `appDiagnosticVideoReadbacks`), the per-case `verdict.json`,
`samples.json`, native/mpv receipts and actual compositor output profile logs.
A build or capture receipt alone is not a numeric pass.

The stock headless output framebuffer and capture are eight bits per channel;
PQ codes still represent values above SDR white, with quantization tolerance.
This tests the software color pipeline, not ten-bit scanout, connector HDR
infoframes, hardware driver interoperability or a monitor's light output.
The diagnostic capture authority is loaded only into an isolated finite-lived
compositor and grants no permission to capture the user's desktop. Cleanup
terminates the exact owned compositor PID and removes its private runtime dir.
