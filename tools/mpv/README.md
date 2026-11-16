# libmpv for Android

`build.sh` builds the libraries in `mpv/src/main/jniLibs/<abi>/` from the pinned sources in `sources.txt`, for
arm64-v8a, armeabi-v7a and x86_64 at API level 23 with Android NDK r26b (26.1.10909125). Gradle never compiles
native code; the prebuilt libraries are committed, as FFmpeg's are for `media3-ffmpeg`.

```
docker build -t pictureshow-mpv-build tools/mpv
docker run --rm -v "$ANDROID_NDK_HOME:/ndk:ro" -v "$PWD:/src" -v "$WORK:/work" \
  -e NDK=/ndk -e WORK=/work -e ABIS="arm64-v8a armeabi-v7a x86_64" pictureshow-mpv-build /src/tools/mpv/build.sh
```

`build.sh` downloads each archive, checks its SHA-256 against `sources.txt` (libplacebo is a git checkout at the
pinned commit, because its release archives omit the submodules it builds with), builds in dependency order and runs
`check.sh` on the result.

## What it builds

| Library | Version | License | Linked |
|---|---|---|---|
| mpv (libmpv, `-Dgpl=false`) | 0.42.0 | LGPL-2.1-or-later | `libmpv.so` |
| FFmpeg (`--build-suffix=_mpv`, no `--enable-gpl/version3/nonfree`) | 8.1.3 | LGPL-2.1-or-later | `libavcodec_mpv.so` and the other FFmpeg libraries, shared |
| libplacebo (OpenGL ES, no Vulkan) | 7.387.0 | LGPL-2.1-or-later | static, inside `libmpv.so` |
| libass | 0.17.5 | ISC | static, inside `libmpv.so` |
| dav1d | 1.5.4 | BSD-2-Clause | static, inside `libavcodec_mpv.so` |
| FreeType | 2.15.0 | FreeType License (FTL) | static |
| FriBidi | 1.0.18 | LGPL-2.1-or-later | static |
| HarfBuzz | 15.1.0 | MIT ("Old MIT") | static |
| Fontconfig | 2.18.3 | MIT-style (HPND) | static |
| Expat | 2.10.0 | MIT | static |
| libc++ (NDK) | r26b | Apache-2.0 WITH LLVM-exception | static |

libplacebo also compiles in fast_float (MIT / Apache-2.0 / BSL-1.0) and a glad-generated OpenGL loader (glad: MIT).
Its other submodules (Jinja, MarkupSafe, Vulkan-Headers) run or are read only at build time.

The FFmpeg libraries carry a `_mpv` suffix so they never collide with `media3-ffmpeg`'s audio-only FFmpeg 6.0.1 in
the same APK. FFmpeg is built without TLS: mpv reads the server through the app's loopback proxy
(`app/.../MpvHttpProxy.kt`), which uses the app's own HTTPS client.

## Replacing the libraries

The LGPL lets you use your own build of mpv, FFmpeg, libplacebo or FriBidi. Rebuild with this script after changing
the source or `build.sh`, or substitute any build with the same sonames and libmpv's client API 2.x, then repack the
APK with your libraries in `lib/<abi>/` (`pictureshow_mpv` loads `libmpv.so` by name, and `libmpv.so` loads the FFmpeg
libraries by name). The JNI glue, `mpv/src/main/jni/`, is MPL-2.0 and built by the same script.

## Size

`check.sh` prints each library's size. AGP strips them and stores them uncompressed and 16 KB-aligned, so the mpv set
adds about 26.3 MB per arm64-v8a APK, 23.8 MB for armeabi-v7a and 30.4 MB for x86_64 (measured from the debug APK's
`lib/<abi>/` entries); a Play App Bundle delivers one ABI per device. libavcodec is about half of it.

## Checks

`check.sh` fails the build when a library lacks 16 KB-aligned LOAD segments, needs a library that is neither
packaged nor an Android system library, embeds a build-machine path, reports an FFmpeg license other than
"LGPL version 2.1 or later", or reports a GPL mpv configuration.
