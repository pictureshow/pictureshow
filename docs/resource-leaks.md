# Resource leak checks

Debug builds install a VM StrictMode policy before application startup. It detects unclosed
Closeables/SQLite resources, leaked registrations and leaked activities. `StrictMode` logs the
allocating stack; `PictureshowLeaks` also logs each reported violation at error level and counts it
on API 28+. API 23–27 retain the StrictMode log penalty. There is no thread policy, death penalty,
periodic GC or background sampler. Release and deviceTest builds exclude the entire provider and
its manifest, so viewers pay no diagnostic cost.

On an owned API 28+ emulator/device, start a debug build and run:

```sh
python3 tools/check-resource-leaks.py --serial DEVICE --output PRIVATE_EVIDENCE/start
# Exercise playback, track changes, cancelled opens, natural ends and image-heavy browsing.
python3 tools/check-resource-leaks.py --serial DEVICE --output PRIVATE_EVIDENCE/end
```

The command forces two GC/finalizer passes, waits briefly for asynchronous reporting, records the process's
PSS/native heap, threads, FD listing and a bounded logcat snapshot (last 5,000 buffer lines), and exits nonzero if the counter is nonzero. Compare
like-for-like idle screens after the player is closed; image/code caches can grow on first use.
Use multiple checkpoints to distinguish retained resources from a warmed cache. Keep the raw
artifacts private: platform/library logs can include server URLs or media metadata.

Each checkpoint also saves task IDs and kernel thread names in `threads.txt`, plus the full
`ls -l` FD targets in `fd-targets.txt`. Diff matched checkpoints by name and target as well as
by count: idle network workers, sockets, codec handles and unreleased playback threads have
different owners. Kernel thread names may be truncated, and tasks/FDs can disappear between
the separate samples. `java-threads.txt` adds full Java names and stacks to identify workers;
native-only threads are absent from that Java dump. A count increase alone does not establish
an unbounded leak.

Verify the platform detector itself in a fresh process:

```sh
adb -s DEVICE shell am force-stop app.pictureshow
adb -s DEVICE shell am start -n app.pictureshow/.MainActivity
python3 tools/check-resource-leaks.py --serial DEVICE --self-test --output PRIVATE_EVIDENCE/canary
```

This first opens and closes a file, then deliberately abandons another. The closed control must
report zero violations and the abandoned file must produce a counted allocating stack. Restart
the process afterward to exclude the intentional leak from a real session. The debug provider
requires Android's `DUMP` permission (held by adb shell); ordinary apps cannot trigger its GC or
canary. It exposes no account data.

A VM checkpoint is a diagnostic, not a deterministic heap proof: GC timing and StrictMode's
repeated-stack throttling can undercount leaks, and native objects without CloseGuard are outside
its coverage. OkHttp pool cleanup can report a collected call minutes after a checkpoint, so keep
logcat running through a final idle window of at least six minutes. Read stacks instead of inferring owners from `dispose`, `release` or `end`. Do not
silence library warnings to obtain a green run. Library defects should retain their source-backed
attribution and reproduction evidence.

The ordinary JVM gate includes a wire-level replacement test: repeated playback factories must
reuse a connection while sending each decision's own headers; overlapping replacement loaders must
make progress and preserve those headers too. This cheaply guards the owned
transport lifetime without adding a long or GC-dependent test to `check`. Long device sessions
and the real canary remain explicit device checks.

## Response cancellation ownership

Ktor 3.6.0's OkHttp callback can discard a response if the call is cancelled before the
engine resumes and registers its body cleanup. OkHttp's own cancellation check runs before
that callback, so another cancellation check in an interceptor alone cannot close this gap.

The API and playback clients' first application interceptor gives each body an atomic ownership handoff.
Calling `source()` claims the body for Ktor, which registers its cleanup before requesting the
source. Cancellation sweeps only an unclaimed body and closes it on the IO dispatcher.
A normal close and a sweep compete for one close claim; a late source request after a sweep
fails as closed. This avoids both blocking the cancelling thread and closing a source while
Ktor reads it. Media3 1.11.1's OkHttpDataSource has a similar handoff: an interrupted loader cancels the call,
then its callback can deliver a response into an abandoned future. It stores the response before
claiming the body, so the same guard covers that cancellation without touching an active reader.
Image clients do not use this interceptor.

The contract depends on the pinned engine's cleanup-before-source order and on no outer
interceptor reading or peeking at the body. Keep the guard first if adding API interceptors.
The callback test deliberately drops a response after cancellation; the Ktor test holds the
engine dispatch after callback delivery to exercise prompt cancellation before body ownership.
The playback test parks the callback after OkHttp's own cancellation check and interrupts the
real Media3 open before delivering its response, with successful and HTTP-error controls.
Reassess these workarounds when upgrading Ktor or Media3, and remove them once each library
itself closes every discarded response. Do not extend request lifetimes or suppress leak reports to hide the gap.
