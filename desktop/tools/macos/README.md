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

## Packaging Pictureshow on macOS

Build on macOS 15 or later with Xcode command-line tools, Python 3.10+, Meson, Ninja, pkg-config and the repository's Gradle prerequisites. Intel builds also need NASM. Build on the target architecture. The script pins and fetches the appropriate Temurin 21 JDK and libplacebo's build-time Python modules. Use `PICTURESHOW_VERSION` and `PICTURESHOW_BUILD_NUMBER` for the marketing and build versions. The package uses the marketing version in About and in the Jellyfin client identity.

Both channels use the Apple-registered `app.pictureshow` bundle ID for one app identity and universal purchase. The launcher UUID is derived from the ID and the stock launcher UUID. It differs from the stock launcher and changes with it when the JDK changes. The app's Local Network purpose string covers Jellyfin's UDP broadcast discovery and direct LAN access. Bonjour service entries and the multicast entitlement apply to different APIs or platforms. [Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)

```sh
PICTURESHOW_MAC_APP_IDENTITY='Developer ID Application: <team name>' \
PICTURESHOW_MAC_NOTARY_SECRET='<broker entry for the App Store Connect API key>' \
desktop/tools/package-macos.sh --output desktop/build/macos-dist
```

The button in the license viewer opens source archives inside the app. Publish the matching Temurin source archive beside the download. The DMG is signed and submitted to Apple's notary service, stapled and assessed before it is copied to the output directory. Keep the signing and notarization reports from the working directory. `--unsigned` builds a local ad-hoc app image for packaging checks. Set `PICTURESHOW_MAC_JDK_ARCHIVE` to reuse an already downloaded pinned JDK. Set `PICTURESHOW_MAC_NATIVE_BUILD` to reuse the release builder's native output for the same architecture and revision. A signed build that reuses `PICTURESHOW_MAC_NATIVE_BUILD` or `PICTURESHOW_MAC_RUNTIME` also needs `PICTURESHOW_MAC_INPUTS_RUN`, the id of the Desktop app run that built them. The script accepts only a successful push run of this repository at the checkout's exact commit, and pull request runs never upload these artifacts. Normal release runs build these libraries from pinned source in a new directory.

The release gate uses Apple's `spctl -t open --context context:primary-signature` assessment for disk images. The evidence also records the `-t install` assessment and its exit status. [Apple Technical Note TN2206](https://developer.apple.com/library/archive/technotes/tn2206/)

For a local packaging run, `PICTURESHOW_MAC_RUNTIME` accepts the JAR directory from the macOS desktop CI artifact. Verify that the run built the same application source revision and architecture. Packaging records the input JAR digests.

The hardened runtime allows HotSpot JIT. The package signs the runtime, the player and every Mach-O in the retained JVM archives using the same identity. App images with either signing channel can be verified with `codesign --verify --deep --strict`. See the package's `otool-dependencies.txt` for the native linkage audit and `LICENSE-INVENTORY.md` for sources and licensing.

### Mac App Store

Set `PICTURESHOW_MAC_APP_IDENTITY` to the Apple Distribution identity, `PICTURESHOW_MAC_INSTALLER_IDENTITY` to the matching 3rd Party Mac Developer Installer identity and `PICTURESHOW_MAC_PROFILE` to the MAC_APP_STORE profile. Run `package-macos.sh --app-store`. The script uses the profile's app and team IDs, embeds the profile, compiles and signs the Swift bridge, and signs the installer. Validate the resulting pkg with `xcrun altool --validate-app` and App Store Connect API authentication.

App Sandbox grants client TCP for Jellyfin HTTP and WebSocket connections. The network server entitlement lets discovery receive replies on an ephemeral UDP socket and lets the playback proxy listen on loopback. Remote control and Watch together use Jellyfin's WebSocket. HotSpot uses the JIT entitlement, and Java's process launcher inherits the app sandbox. Accounts, preferences and downloads default to the app container. The optional download folder is authorized through a user-selected read-write directory and an app-scoped bookmark. A folder change takes effect on relaunch, and each folder retains its own download library. [Apple network server entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.server) · [Apple App Sandbox](https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox)

The JVM's `StoreKitBridge` exposes App Store products, purchase outcomes, verified current entitlements and verified transaction updates. A fake native transport exercises the JVM bridge in tests. The expected product ID is `app.pictureshow.supporter.monthly`. The purchase screen, App Store Connect product and iOS integration are separate entries. See the license inventory for the counsel question before store distribution.
