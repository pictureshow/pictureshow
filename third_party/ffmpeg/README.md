# FFmpeg

`media3-ffmpeg/src/main/jniLibs/<abi>/` contains `libavcodec.so`, `libavutil.so` and `libswresample.so` from
FFmpeg, built as shared libraries from unmodified source. `libffmpegJNI.so` beside them is Media3's JNI wrapper
(Apache-2.0, `media3-ffmpeg/src/main/jni`), linked dynamically against them.

| | |
|---|---|
| Version | 6.0.1 (the 6.0 release line Media3 1.11.1's `decoder_ffmpeg` README names) |
| Source | https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz |
| SHA-256 | `9b16b8731d78e596b4be0d720428ca42df642bb2d78342881ff7f5bc29fc9623` |
| Signature | https://ffmpeg.org/releases/ffmpeg-6.0.1.tar.xz.asc (FFmpeg release key `FCF986EA15E6E293A5644F10B4322F04D67658D8`) |
| License | GNU Lesser General Public License 2.1 or later ([COPYING.LGPLv2.1](COPYING.LGPLv2.1)) |
| Toolchain | Android NDK r26b (26.1.10909125), API level 23 |

The build uses no `--enable-gpl`, `--enable-nonfree` or `--enable-version3`, so the libraries are LGPL 2.1 or later;
`avcodec_license()` in each build reports "LGPL version 2.1 or later". Only audio decoders are enabled.

Rebuild them with `tools/ffmpeg/build.sh` (see `tools/ffmpeg/README.md`), or substitute your own build of
FFmpeg 6.0.x (libavcodec 60, libavutil 58, libswresample 4, unversioned sonames): `libffmpegJNI.so` links them
dynamically by name, so the app uses whichever copies are packaged in `jniLibs`.

## Configure lines

The exact lines, as embedded in each library (`avcodec_configuration()`), per ABI:

armeabi-v7a:

```
--arch=arm --cpu=armv7-a --cc=armv7a-linux-androideabi23-clang --cxx=armv7a-linux-androideabi23-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip --extra-cflags='-march=armv7-a -mfloat-abi=softfp' --extra-ldflags='-Wl,--fix-cortex-a8 -Wl,-z,max-page-size=16384' --target-os=android --enable-cross-compile --disable-static --enable-shared --disable-doc --disable-programs --disable-everything --disable-autodetect --disable-network --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan --enable-decoder=aac --enable-decoder=mp3 --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=truehd --enable-decoder=mlp --enable-decoder=dca --enable-decoder=vorbis --enable-decoder=opus --enable-decoder=amrnb --enable-decoder=amrwb --enable-decoder=flac --enable-decoder=alac --enable-decoder=pcm_mulaw --enable-decoder=pcm_alaw
```

arm64-v8a:

```
--arch=aarch64 --cpu=armv8-a --cc=aarch64-linux-android23-clang --cxx=aarch64-linux-android23-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip --extra-cflags= --extra-ldflags=' -Wl,-z,max-page-size=16384' --target-os=android --enable-cross-compile --disable-static --enable-shared --disable-doc --disable-programs --disable-everything --disable-autodetect --disable-network --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan --enable-decoder=aac --enable-decoder=mp3 --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=truehd --enable-decoder=mlp --enable-decoder=dca --enable-decoder=vorbis --enable-decoder=opus --enable-decoder=amrnb --enable-decoder=amrwb --enable-decoder=flac --enable-decoder=alac --enable-decoder=pcm_mulaw --enable-decoder=pcm_alaw
```

x86:

```
--arch=x86 --cpu=i686 --disable-asm --cc=i686-linux-android23-clang --cxx=i686-linux-android23-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip --extra-cflags= --extra-ldflags=' -Wl,-z,max-page-size=16384' --target-os=android --enable-cross-compile --disable-static --enable-shared --disable-doc --disable-programs --disable-everything --disable-autodetect --disable-network --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan --enable-decoder=aac --enable-decoder=mp3 --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=truehd --enable-decoder=mlp --enable-decoder=dca --enable-decoder=vorbis --enable-decoder=opus --enable-decoder=amrnb --enable-decoder=amrwb --enable-decoder=flac --enable-decoder=alac --enable-decoder=pcm_mulaw --enable-decoder=pcm_alaw
```

x86_64:

```
--arch=x86_64 --cpu=x86-64 --disable-asm --cc=x86_64-linux-android23-clang --cxx=x86_64-linux-android23-clang++ --nm=llvm-nm --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip --extra-cflags= --extra-ldflags=' -Wl,-z,max-page-size=16384' --target-os=android --enable-cross-compile --disable-static --enable-shared --disable-doc --disable-programs --disable-everything --disable-autodetect --disable-network --disable-avdevice --disable-avformat --disable-swscale --disable-postproc --disable-avfilter --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan --enable-decoder=aac --enable-decoder=mp3 --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=truehd --enable-decoder=mlp --enable-decoder=dca --enable-decoder=vorbis --enable-decoder=opus --enable-decoder=amrnb --enable-decoder=amrwb --enable-decoder=flac --enable-decoder=alac --enable-decoder=pcm_mulaw --enable-decoder=pcm_alaw
```

