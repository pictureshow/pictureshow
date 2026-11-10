# SPDX-License-Identifier: MPL-2.0
cmake_minimum_required(VERSION 3.21)
project(PictureshowCoreSample LANGUAGES CXX)
find_package(Qt6 6.5 REQUIRED COMPONENTS Core)
add_library(pictureshow_core_sample STATIC core.cpp)
target_compile_features(pictureshow_core_sample PUBLIC cxx_std_17)
target_link_libraries(pictureshow_core_sample PUBLIC Qt6::Core)
target_include_directories(pictureshow_core_sample PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
add_executable(pictureshow_vectors vectors.cpp)
target_link_libraries(pictureshow_vectors PRIVATE pictureshow_core_sample)
enable_testing()
set(PICTURESHOW_SPEC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../spec/vectors" CACHE PATH "Unchanged parity vector directory")
add_test(NAME cpp_parity_sample COMMAND pictureshow_vectors
    "${PICTURESHOW_SPEC_ROOT}/playback-mode.json"
    "${PICTURESHOW_SPEC_ROOT}/music-session.json"
    "${PICTURESHOW_SPEC_ROOT}/music-reports.json"
    "${PICTURESHOW_SPEC_ROOT}/music-quality.json")
