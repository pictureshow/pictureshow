# Packaging Pictureshow on macOS

Build on macOS 15 or later with Xcode command-line tools, Python 3.10+ with Jinja2, Meson, Ninja, pkg-config and the repository's Gradle prerequisites. Intel builds also need NASM. Build on the target architecture. The script pins and fetches the appropriate Temurin 21 JDK. Use `PICTURESHOW_VERSION` and `PICTURESHOW_BUILD_NUMBER` for the marketing and build versions. The package uses the marketing version in About and in the Jellyfin client identity.

Both channels use the Apple-registered `app.pictureshow` bundle ID for one app identity and universal purchase. The launcher UUID is derived from the ID and the stock launcher UUID. It differs from the stock launcher and changes with it when the JDK changes. The app's Local Network purpose string covers Jellyfin's UDP broadcast discovery and direct LAN access. Bonjour service entries and the multicast entitlement apply to different APIs or platforms. [Apple TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)

```sh
PICTURESHOW_MAC_APP_IDENTITY='Developer ID Application: <team name>' \
PICTURESHOW_MAC_NOTARY_SECRET='<broker entry for the App Store Connect API key>' \
desktop/tools/package-macos.sh --output desktop/build/macos-dist
```

The button in the license viewer opens source archives inside the app. Publish the matching Temurin source archive beside the download. The DMG is signed and submitted to Apple's notary service, stapled and assessed before it is copied to the output directory. Keep the signing and notarization reports from the working directory. `--unsigned` builds a local ad-hoc app image for packaging checks. Set `PICTURESHOW_MAC_JDK_ARCHIVE` to reuse an already downloaded pinned JDK. Set `PICTURESHOW_MAC_NATIVE_BUILD` to reuse the release builder's native output for the same architecture and revision. Normal release runs build these libraries from pinned source in a new directory.

The hardened runtime allows HotSpot JIT. The package signs the runtime, the player and every Mach-O in the retained JVM archives using the same identity. App images with either signing channel can be verified with `codesign --verify --deep --strict`. See the package's `otool-dependencies.txt` for the native linkage audit and `LICENSE-INVENTORY.md` for sources and licensing.

## Mac App Store

Set `PICTURESHOW_MAC_APP_IDENTITY` to the Apple Distribution identity, `PICTURESHOW_MAC_INSTALLER_IDENTITY` to the matching 3rd Party Mac Developer Installer identity and `PICTURESHOW_MAC_PROFILE` to the MAC_APP_STORE profile. Run `package-macos.sh --app-store`. The script uses the profile's app and team IDs, embeds the profile, compiles and signs the Swift bridge, and signs the installer. Validate the resulting pkg with `xcrun altool --validate-app` and App Store Connect API authentication.

App Sandbox grants client TCP for Jellyfin HTTP and WebSocket connections. Discovery binds an ephemeral UDP socket and receives replies, so it also needs the network server entitlement. Remote control and Watch together use Jellyfin's existing WebSocket rather than a local TCP listener. HotSpot uses the JIT entitlement. Accounts, preferences and downloads default to the app container. The optional download folder is authorized through a user-selected read-write directory and an app-scoped bookmark. A folder change takes effect on relaunch, and each folder retains its own download library. [Apple UDP entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.network.server) · [Apple App Sandbox](https://developer.apple.com/documentation/xcode/configuring-the-macos-app-sandbox)

The JVM's `StoreKitBridge` exposes App Store products, purchase outcomes, verified current entitlements and verified transaction updates. A fake native transport exercises the JVM bridge in tests. The expected product ID is `app.pictureshow.supporter.monthly`. The purchase screen, App Store Connect product and iOS integration are separate entries. See the license inventory for the counsel question before store distribution.
