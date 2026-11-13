# Mac frame pacing evidence

The selected adapter keeps three RGBA16Float IOSurfaces. A render-thread GL fence
must signal before Metal reads a slot; Metal completion must precede GL reuse.
The normal path has no CPU pixel readbacks or synchronous completion waits.
The default 50 ms mpv render headroom supplies target times to timed Metal presents.
The source is pinned to mpv 0.42: its implementation returns target_time in ns,
although that version's render.h text says microseconds. A future target more than
one second away fails instead of retaining a drawable indefinitely.

Set `SPIKE_MAC_FRAME_TRACE` to a fresh JSONL path for opt-in diagnostics. Events are
buffered in memory and flushed at teardown. The file records render begin/end and
actual thread QoS, fence issue/noticed-ready, drawable acquisition begin/end,
requested target, Metal GPU start/end/status, and `MTLDrawable.presentedTime` from
its presented handler. A zero presented time is a skipped/unpresented drawable;
it is not replaced by a submission timestamp. Trace status records overflow and
handlers still pending after a bounded teardown wait. Missing/overflowed evidence
cannot establish a pass.

Visibility events record both the transparent Compose window and its Metal child,
AppKit occlusion state, app activation, frontmost PID, window number and host time.
All windows and presentation timestamps use the same Mach host-time seconds.
AppKit isVisible alone does not establish that the video is unoccluded. Infer
missed nominal refresh slots only between nonzero actual presentation timestamps;
that is not a physical panel scanout measurement. Fence timing includes the delay
until the render thread notices completion. System GPU busy and connected GPU
clients are correlation evidence, not per-process GPU attribution.

`SPIKE_SOAK_FULLSCREEN=1` keeps the standard probe's final sustain phase in its
borderless fullscreen geometry after testing restore. `SPIKE_MAC_TEST_ONTOP=1`
raises the test window during a coordinated shared-display experiment; ordinary
playback does not use this setting. Match native mpv's `--ontop=yes` in that control
arm and report this environment constraint. A valid visible-playback comparison
requires no occlusion throughout the stable interval, the same actual display and
refresh, normal launch priority, source dimensions/fps, VideoToolbox and audio
clock, elapsed/media progress, explicit non-EOF, and decoder/VO deltas. Preserve
failed and contaminated trials, rather than replacing them with passing repeats.

For independent numeric HDR verification, `SPIKE_HDR_READBACK_DIR` enables one
actual-drawable FP16 readback after warmup. This diagnostic waits once for its
Metal copy and writes tightly packed rows plus declared units. Leave it unset for
all pacing runs. Mac output preserves the full PQ domain with target-peak 10000;
mpv's 203-nit linear units are converted to 100-nit EDR units on an initial HDR
screen, while a headroom-1 screen retains reference 203. This startup selection
does not qualify later cross-display or changing-headroom adaptation.
