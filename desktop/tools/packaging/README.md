# Build Pictureshow for Windows and Linux

Both entry points build the application from the current checkout using the
pinned Temurin JDK. They download and verify the media inputs, compile shared
FFmpeg and libmpv, walk the native import closure, and query the shipped
FFmpeg libraries for their licensing and configuration. The source offer,
notices, build configuration and binary hashes are also included in the app
under Settings → Licenses → Playback Sources.

## Windows x64

Run `bash desktop/tools/package-windows.sh [output-directory]` in UCRT64 with
the tools installed by the desktop packages workflow, x64 MSVC, the Windows
SDK, and WiX 3. The default output is `desktop/build/windows-package`.

- `Pictureshow-VERSION.msi` is the unsigned direct download. Windows Installer
  registers a per-user Start menu entry and an uninstall entry in Settings.
  Windows may show SmartScreen when the MSI is opened from the web.
- `Pictureshow-VERSION-x64.msix` is the unsigned Microsoft Store package.
  Before building an actual submission, set `PICTURESHOW_STORE_IDENTITY_NAME`
  and `PICTURESHOW_STORE_PUBLISHER` to the two values from Partner Center. The
  defaults identify the disposable CI package. The product ID to reserve for
  the auto-renewing one-month Supporter add-on is `supporter.monthly`, at
  USD 2.99 in Partner Center. The Store supplies local prices and signs its
  certified packages. See `WindowsSupporterStore.kt` for the shared UI seam.
- Full-trust Win32 access in the MSIX is required for the existing Java/Compose
  desktop executable, native rendering, LAN discovery, and local playback.
  The desktop code does not need a Store SDK or product catalog for the MSI.

The hosted job signs a separate test copy using an ephemeral certificate,
checks the installed MSIX with the SDK's certification kit, runs the disposable
Jellyfin smoke through the packaged launcher, and checks the direct MSI install
and uninstall. The unsigned packages are the only distribution outputs.

## Linux x86_64

Run `bash desktop/tools/package-linux.sh [output-directory]` with the Ubuntu
build tools from the workflow. The default output is
`desktop/build/linux-package`. Mark the AppImage executable after download.
The host needs Wayland with XWayland for the current video and AWT surfaces,
desktop graphics/audio libraries, and the Ubuntu 24.04-era glibc ABI or newer.
Set `APPIMAGE_EXTRACT_AND_RUN=1` on hosts where FUSE is unavailable.

The Flatpak ID is `app.pictureshow.Pictureshow`, using the project's
pictureshow.app DNS namespace in reverse and the application name as its
component. The manifest uses Freedesktop 25.08 and the same locally generated
payload as the AppImage. The workflow builds a local bundle and validates the
desktop entry and AppStream metadata. The manifest's local payload source is
for this checkout build. A future Flathub submission needs approved listing
metadata and a Flathub-compatible source staging recipe.

## Verification and source correspondence

Hosted package artifacts expire after three days. The workflow only runs on
pushes in the owning repository and builds packages from that push's checkout
without fetching artifacts built by other jobs or runs. Keep the accompanying
matching Temurin source archive beside any eventual distribution. See
[the license inventory](LICENSE-INVENTORY.md) and the checked-in pins for the
native and runtime inputs.
