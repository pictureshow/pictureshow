# FFmpeg for media3-ffmpeg

`media3-ffmpeg` ships Media3's FFmpeg audio extension with prebuilt native libraries in
`media3-ffmpeg/src/main/jniLibs/<abi>/`, so `./gradlew check assembleDebug` needs no NDK. These scripts rebuild
those libraries and the test clips from pinned sources. Licensing details: `third_party/ffmpeg/README.md`.

## Rebuilding the libraries

`build.sh` downloads FFmpeg 6.0.1, checks its SHA-256, and for each of `armeabi-v7a`, `arm64-v8a`, `x86` and
`x86_64`:

1. configures FFmpeg LGPL-only with `--disable-everything` and just the audio decoders Media3's `FfmpegLibrary`
   maps (aac, mp3, ac3, eac3, truehd, mlp, dca, vorbis, opus, amrnb, amrwb, flac, alac, pcm_mulaw, pcm_alaw),
   as shared libraries with 16 KB page alignment;
2. builds Media3's JNI wrapper from `media3-ffmpeg/src/main/jni` with CMake, linked dynamically against them;
3. strips the four libraries into `media3-ffmpeg/src/main/jniLibs/<abi>/`;

then runs `check.sh` over the result.

Requirements: a Linux x86_64 host (or container) with bash, make, perl, curl, xz, sha256sum, binutils `strings`,
CMake 3.22 or later with Ninja, and Android NDK r26b (26.1.10909125), which Media3 1.11.1's extension README names.
With the Android SDK's `sdkmanager`: `sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`.

```sh
NDK=<ndk dir> WORK=<scratch dir> CMAKE=<cmake binary> tools/ffmpeg/build.sh
```

Optional: `OUT` (output directory), `ABIS` (subset of ABIs), `DECODERS` (decoder list), `JOBS` (parallelism).
The libraries embed their configure line; tools are taken from `PATH` and FFmpeg is built in its own tree so no
local directory ends up in the binaries.

## Checking the libraries

```sh
tools/ffmpeg/check.sh media3-ffmpeg/src/main/jniLibs
```

For every ABI it checks that all four libraries exist, sonames are unversioned, LOAD segments are 16 KB aligned,
`libffmpegJNI.so` links the three FFmpeg libraries, the embedded configure line has no `--enable-gpl`,
`--enable-nonfree` or `--enable-version3`, each library reports "LGPL version 2.1 or later", and every expected
decoder is in `libavcodec.so`. It prints the sizes and the configure line and exits non-zero on any failure.
`EXPECTED_DECODERS`, `ABIS` and `READELF` (an `llvm-readelf` or `readelf`) can be set in the environment.

## Test clips

`make-test-clips.sh` writes the synthetic clips that `media3-ffmpeg`'s instrumented tests play
(`media3-ffmpeg/src/androidTest/assets/clips/`): two seconds of sine tones at 48 kHz, a different frequency on
each channel. It needs an `ffmpeg` CLI with libopus, libvorbis and libmp3lame, plus python3 and a C toolchain:

```sh
WORK=<scratch dir> tools/ffmpeg/make-test-clips.sh
```

FFmpeg's encoders stop at 5.1 for E-AC-3, DTS and TrueHD. For the E-AC-3 7.1 clip the script builds a small host
`ffmpeg` from the same pinned source with `test-clips/eac3-dependent-substream.patch`, which lets the E-AC-3
encoder write a dependent substream, and interleaves a 5.1 independent substream with a four-channel dependent one
(`test-clips/interleave_eac3.py`). The patched encoder is a test tool only; it is not part of the app. TrueHD is
tested at 5.1.

## Running the instrumented tests

```sh
./gradlew :media3-ffmpeg:assembleDebugAndroidTest
adb install -r media3-ffmpeg/build/outputs/apk/androidTest/debug/media3-ffmpeg-debug-androidTest.apk
adb shell am instrument -w androidx.media3.decoder.ffmpeg.test/androidx.test.runner.AndroidJUnitRunner
```

Each test plays one clip through ExoPlayer with `FfmpegAudioRenderer` as the only renderer and checks the decoder
name, the PCM channel count and that every channel carries sound; one more checks that `DefaultRenderersFactory`
finds the renderer by reflection.
