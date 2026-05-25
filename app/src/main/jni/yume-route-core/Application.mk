# Copyright (C) 2013 The Android Open Source Project
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

APP_OPTIM := release
APP_PLATFORM := android-29
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_CFLAGS := -O3
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
# c++_shared was chosen over c++_static after observing that c++_static
# affected the link of pre-existing yume-route-core (pure C) and caused
# a SIGABRT in its task system during service shutdown. c++_shared adds
# libc++_shared.so to the APK (~ 600 KiB on arm64) but keeps each .so
# self-contained from a STL-symbol perspective. yume-route-core does not
# use C++ and so does not pull libc++_shared.so as a NEEDED dep.
# libyumetransport.so (the native TLS pipe — see ../../cpp/yume_tls_pipe.cpp)
# needs std::thread / std::string etc. and DOES NEED libc++_shared.so.
APP_STL := c++_shared
