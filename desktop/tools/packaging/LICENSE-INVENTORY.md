# Pictureshow playback sources for Windows and Linux

The application is MPL-2.0. The matching application revision is in
`BUILD-SOURCE.txt`. The package build entry points and the recipes for these
libraries are in `desktop/tools` at that revision.

| Component | License | Matching source and SHA-256 |
| --- | --- | --- |
| mpv 0.42.0 | LGPL-2.1-or-later build | Archive in this directory, pinned in `native-sources.sh` |
| FFmpeg 8.0 | LGPL-2.1-or-later build | Archive in this directory, pinned in `native-sources.sh` |
| dav1d 1.5.4 | BSD-2-Clause | Archive in this directory, pinned in `native-sources.sh` |
| Eclipse Temurin 21.0.13+9 | GPL-2.0 with Classpath Exception and bundled third-party notices | Binary and matching source archive pinned in `inputs.tsv`. Include the matching source archive alongside the installer or image when distributing. |

`ffmpeg-runtime-config.json` records the loaded libraries' license and build
configuration. `mpv-buildoptions.json` records Meson's resolved options.
`library-imports.json` maps the native imports and hashes packaged binaries.
For testing a modified playback library, replace the shared libraries in an
extracted copy of the package or use `MPV_LIBRARY` to point to an externally
built libmpv with its compatible shared dependencies.

## Windows

The UCRT64 binary packages used for the media runtime are pinned in
`ucrt64-sources.tsv`. The official binary archive URL is
`https://repo.msys2.org/mingw/ucrt64/` plus the first-column filename.
The last column names the matching [MSYS2 source recipe directories](https://github.com/msys2/MINGW-packages/tree/fcde6116eb21f5caec9126c3aeca81f78a3ecb79).
The recipes carry the source checksums and any patches. `UCRT64/` has package
and build metadata, and the upstream notices for the packages used in the DLL
closure. This includes the FreeType license choice and the GCC Runtime Library
Exception for the compiler runtime DLLs. Windows system DLLs supply the UCRT,
Direct3D, Schannel and other platform APIs.

## Linux

`render-sources.tsv` pins the bundled libass (ISC), libplacebo (LGPL-2.1),
FreeType (FTL option), FriBidi (LGPL-2.1-or-later), HarfBuzz (MIT) and
libunibreak (zlib) builds along with the code generators and headers used by
libplacebo. Their archives and notices accompany the native binaries.
`inputs.tsv` also pins the Wayland and Weston sources used to generate the
adapter's protocol bindings, with accompanying notices. Standard system libraries
provide the Linux ABI, Wayland/EGL and desktop graphics driver, audio,
Fontconfig and GnuTLS. The AppImage runtime is pinned separately in
`inputs.tsv`; the Flatpak uses the Freedesktop platform runtime.
