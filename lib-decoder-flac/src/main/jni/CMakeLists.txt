#
# Copyright 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

# Enable C++11 features.
set(CMAKE_CXX_STANDARD 11)

# Define project name for your JNI module
project(libflacJNI C CXX)

# Suppress pointer cast alignment warnings from upstream libflac source.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wno-cast-align")

set(libflac_jni_root "${CMAKE_CURRENT_SOURCE_DIR}")

# Build libflac.
add_subdirectory("${libflac_jni_root}/libflac"
                 EXCLUDE_FROM_ALL)

# minSdk 23：32 位 ABI 上 bionic 会把 _FILE_OFFSET_BITS=64 的 fseeko 映射到
# API 24 才声明的 fseeko64。解码只走内存 / 网络流，用 32 位文件偏移就够。
target_compile_definitions(FLAC PRIVATE _FILE_OFFSET_BITS=32)

# Build libflacJNI.
add_library(flacJNI
            SHARED
            flac_jni.cc
            flac_parser.cc)

# Add the include directory from libflac.
include_directories("${libflac_jni_root}/libflac/include")

# Locate NDK log library.
find_library(android_log_lib log)

# Link libflacJNI against used libraries.
target_link_libraries(flacJNI
                      PRIVATE android
                      PRIVATE FLAC
                      PRIVATE ${android_log_lib})
