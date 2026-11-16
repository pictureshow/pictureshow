# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
# The JNI glue between app.pictureshow.mpv.MpvNative and libmpv. MPV_PREFIX is the install prefix of the ABI's
# libmpv/FFmpeg build (tools/mpv/build.sh).
cmake_minimum_required(VERSION 3.22)
project(pictureshow_mpv C)
add_library(pictureshow_mpv SHARED pictureshow_mpv.c)
target_include_directories(pictureshow_mpv PRIVATE ${MPV_PREFIX}/include)
target_compile_options(pictureshow_mpv PRIVATE -Wall -Wextra -Werror -O2)
target_link_directories(pictureshow_mpv PRIVATE ${MPV_PREFIX}/lib)
target_link_libraries(pictureshow_mpv PRIVATE mpv avcodec_mpv log)
target_link_options(pictureshow_mpv PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
