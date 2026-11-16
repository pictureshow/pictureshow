# mpv and its libraries

`mpv/src/main/jniLibs/<abi>/` (arm64-v8a, armeabi-v7a, x86_64) holds libmpv and the libraries it needs, built from
unmodified upstream sources by `tools/mpv/build.sh` (see `tools/mpv/README.md` for how to rebuild). The app uses mpv
only when the viewer picks it, or when Automatic finds that mpv avoids a server conversion (PLAY-60).

| Library | Version | License | Source | SHA-256 / commit |
|---|---|---|---|---|
| mpv | 0.42.0 | LGPL-2.1-or-later (built with `-Dgpl=false`) | https://github.com/mpv-player/mpv/archive/refs/tags/v0.42.0.tar.gz | see `tools/mpv/sources.txt` |
| FFmpeg | 8.1.3 | LGPL-2.1-or-later | https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz | see `tools/mpv/sources.txt` |
| libplacebo | 7.387.0 | LGPL-2.1-or-later | https://code.videolan.org/videolan/libplacebo, tag v7.387.0 | `275f7dea27924cdd34ab2e6c20e448b6bc4fab65` |
| libass | 0.17.5 | ISC | https://github.com/libass/libass/releases | see `tools/mpv/sources.txt` |
| dav1d | 1.5.4 | BSD-2-Clause | https://code.videolan.org/videolan/dav1d | see `tools/mpv/sources.txt` |
| FreeType | 2.15.0 | FreeType License | https://download.savannah.gnu.org/releases/freetype/ | see `tools/mpv/sources.txt` |
| FriBidi | 1.0.18 | LGPL-2.1-or-later | https://github.com/fribidi/fribidi/releases | see `tools/mpv/sources.txt` |
| HarfBuzz | 15.1.0 | MIT ("Old MIT") | https://github.com/harfbuzz/harfbuzz/releases | see `tools/mpv/sources.txt` |
| Fontconfig | 2.18.3 | MIT-style | https://gitlab.freedesktop.org/fontconfig/fontconfig | see `tools/mpv/sources.txt` |
| Expat | 2.10.0 | MIT | https://github.com/libexpat/libexpat/releases | see `tools/mpv/sources.txt` |
| libc++ (Android NDK r26b) | 26.1.10909125 | Apache-2.0 WITH LLVM-exception | Android NDK | — |

`tools/mpv/sources.txt` holds every exact URL and checksum. libplacebo also compiles in fast_float (MIT, also
Apache-2.0 or BSL-1.0) and a glad-generated OpenGL loader (MIT).

## License texts

[COPYING.LGPLv2.1](COPYING.LGPLv2.1) (mpv, FFmpeg, libplacebo, FriBidi), [mpv.Copyright](mpv.Copyright),
[libass.COPYING](libass.COPYING), [dav1d.COPYING](dav1d.COPYING), [freetype.FTL.TXT](freetype.FTL.TXT),
[harfbuzz.COPYING](harfbuzz.COPYING), [fontconfig.COPYING](fontconfig.COPYING), [expat.COPYING](expat.COPYING),
[fast_float.LICENSE-MIT](fast_float.LICENSE-MIT), [glad.LICENSE](glad.LICENSE), [libcxx.LICENSE.TXT](libcxx.LICENSE.TXT).
The app's Settings → About → Open-source licenses lists each of them.

## LGPL compliance

- No GPL code: mpv is configured with `-Dgpl=false` (its build reports the `lgpl` feature), and FFmpeg without
  `--enable-gpl`, `--enable-version3` or `--enable-nonfree`; `avcodec_license()` reports "LGPL version 2.1 or later".
  `tools/mpv/check.sh` fails the build otherwise.
- mpv and FFmpeg are shared libraries (`libmpv.so`, `lib*_mpv.so`) that the APK loads by name, so a viewer can
  replace them with their own builds. libplacebo and FriBidi are linked statically into `libmpv.so`; replacing them
  means relinking `libmpv.so`, which `tools/mpv/build.sh` does from source with all the object files' inputs public.
- The app's own JNI glue (`mpv/src/main/jni`) and Kotlin binding are MPL-2.0.
