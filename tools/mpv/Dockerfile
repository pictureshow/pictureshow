# Build environment for tools/mpv/build.sh. The Android NDK r26b (26.1.10909125) is mounted at /ndk.
FROM debian:trixie
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash ca-certificates curl xz-utils git make cmake ninja-build pkg-config python3-pip nasm gperf python3 \
      python3-jinja2 python3-markupsafe file binutils perl patch gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*
# Fontconfig 2.18 needs Meson 1.11 or later; trixie packages 1.7.
RUN pip install --no-cache-dir --break-system-packages meson==1.12.1
