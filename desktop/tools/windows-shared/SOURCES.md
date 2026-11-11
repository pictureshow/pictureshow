# Source, recipe and notice provenance

This describes the public diagnostic artifact from CI run
[42885965595](https://github.com/pictureshow/pictureshow/actions/runs/42885965595),
commit `b2fd5baa112d0ad3bfc8bf2262924b7e53838827`. Qualification limits are in
[README.md](README.md); this is not a complete distributable release source offer.

## mpv and FFmpeg

mpv v0.42.0 comes from the [source tag archive](https://github.com/mpv-player/mpv/archive/refs/tags/v0.42.0.tar.gz),
SHA256 `bcfebbfa2b65a6d88dc97ea18117831072373e38b5785a2afb874ed4df5070dd`.
The exact local build recipe is [build.sh](build.sh). The artifact copies
`LICENSE.LGPL` and `Copyright` directly from the verified source tree. Meson build
options/dependency paths and the loaded mpv-configuration property are retained.

FFmpeg uses the [pinned LGPL-shared archive](https://github.com/FFmpeg-Builds/FFmpeg-Builds/releases/download/autobuild-2026-11-10-12-39/ffmpeg-n8.1.3-34-ga35157abb9-win64-lgpl-shared-8.1.zip),
SHA256 `3045c2213ed8cbb05edf0d5a631b4490061de3b7567ec145644156e7c98da2c6`.
Its release tag points to immutable [build-recipe commit c9fe5114060e507409f7ee07edfd82efaf25d767](https://github.com/FFmpeg-Builds/FFmpeg-Builds/tree/c9fe5114060e507409f7ee07edfd82efaf25d767).
The runtime identifies FFmpeg source revision `a35157abb9`, version
`n8.1.3-34-ga35157abb9-20261110`. The artifact copies LICENSE.txt directly from
that archive and calls each loaded FFmpeg library's license/configuration API.
All six report LGPL version 3 or later. Its statically incorporated external
components remain part of that upstream recipe and require complete source and
notice correspondence before a release; direct FFmpeg DLL imports do not prove
those internal components are shared.

## MSYS shared dependencies

`runtime-manifest.json` hashes every collected binary. `windows-pe-imports.json`
records the complete recursive PE import graph; unresolved non-system DLLs fail
the collector. For each MSYS-owned runtime DLL, `msys-runtime-licenses/` retains
`pacman -Qi` package/version/license metadata and original files copied from its
installed `share/licenses` directory. The recipe snapshot below matches the
observed installed package versions/releases; its PKGBUILDs carry the upstream
source URLs, checksums and build options. The workflow currently tracks the MSYS
repository rather than locking future runs to these package versions.

| Runtime package | Observed version | Matching source/build recipe |
| --- | --- | --- |
| brotli | 1.2.1-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-brotli/PKGBUILD) |
| bzip2 | 1.0.8-4 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-bzip2/PKGBUILD) |
| expat | 2.10.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-expat/PKGBUILD) |
| fontconfig | 2.18.3-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-fontconfig/PKGBUILD) |
| freetype | 2.15.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-freetype/PKGBUILD) |
| fribidi | 1.0.18-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-fribidi/PKGBUILD) |
| gettext-runtime | 1.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-gettext/PKGBUILD) |
| glib2 | 2.90.1-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-glib2/PKGBUILD) |
| graphite2 | 1.3.15-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-graphite2/PKGBUILD) |
| harfbuzz | 15.1.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-harfbuzz/PKGBUILD) |
| lcms2 | 2.20-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-lcms2/PKGBUILD) |
| libass | 0.17.5-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-libass/PKGBUILD) |
| libdovi | 3.4.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-libdovi/PKGBUILD) |
| libgcc | 16.2.0-6 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-gcc/PKGBUILD) |
| libiconv | 1.19.1-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-libiconv/PKGBUILD) |
| libplacebo | 7.387.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-libplacebo/PKGBUILD) |
| libpng | 1.6.59-2 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-libpng/PKGBUILD) |
| libstdc++ | 16.2.0-6 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-gcc/PKGBUILD) |
| libunibreak | 7.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-libunibreak/PKGBUILD) |
| libwinpthread | 14.0.0.r598.ge3d18511f-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-winpthreads/PKGBUILD) |
| pcre2 | 10.49-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-pcre2/PKGBUILD) |
| shaderc | 2026.4-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-shaderc/PKGBUILD) |
| spirv-cross | 1~1.4.363.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-spirv-cross/PKGBUILD) |
| vulkan-loader | 1~1.4.363.0-1 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-vulkan-loader/PKGBUILD) |
| zlib | 1.4-2 | [PKGBUILD](https://github.com/msys2/MINGW-packages/blob/d8ebbcd39aa10a25e2ba4c04b48da82206634864/mingw-w64-zlib/PKGBUILD) |

`libstdc++-6.dll` and `libgcc_s_seh-1.dll` come from GCC 16.2.0-6 under
GPL-3.0-or-later WITH GCC Runtime Library Exception 3.1. Both packages' original
COPYING3 and COPYING.RUNTIME are retained. Their metadata also lists GFDL for
GCC documentation. `libwinpthread-1.dll` is MIT/BSD-3-Clause.

FreeType offers FTL as the alternative to GPL. The gettext package includes GPL
utilities and the LGPL libintl runtime; only the imported `libintl-8.dll` is
collected, with COPYING and COPYING.LIB retained. The libiconv metadata labels
its runtime LGPL and documentation GPL. The lcms2 package lists MIT/GPL because
it also supplies utilities; the collected lcms2 library has its original MIT
LICENSE retained. Package-level mixed-license metadata is preserved rather than
flattened into a blanket license claim. libass is ISC; libplacebo is LGPL-2.1;
the remaining runtime notices are retained with their owning packages.

The fixture-generation Chocolatey FFmpeg is a separate tool and is excluded from
both the runtime artifact and the LayerProbe PATH. Only generated CC0 media and
its license manifests are used. No GPL client implementation was copied.
