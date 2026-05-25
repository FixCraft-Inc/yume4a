# Copyright (C) 2021 The Android Open Source Project
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

TOP_PATH := $(call my-dir)

ifeq ($(filter $(modules-get-list),yaml),)
    include $(TOP_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
    include $(TOP_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),yume-task-system),)
    include $(TOP_PATH)/third-part/yume-task-system/Android.mk
endif

LOCAL_PATH = $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := yume-route-core
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/src/core/src \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/yume-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip yume-task-system
# liblog provides __android_log_print used by yume-bridge-jni.c diagnostic output.
LOCAL_LDLIBS += -llog
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)

# ----------------------------------------------------------------------
# libyumetransport.so — native TLS pipe (OpenSSL non-blocking I/O behind
# a JNI surface). Replaces SSLSocket.outputStream.write() on the upload
# hot path so the writer coroutine never blocks on kernel TCP buffer
# fill — the actual cause of the ~28 Mbps cap. See app/src/main/cpp/
# yume_tls_pipe.cpp and YumeNativeTlsPipe.kt for the contract.
#
# OpenSSL static libs are pre-built per ABI by app/src/main/cpp/
# build-openssl.sh, invoked from a Gradle task that runs before
# externalNativeBuild — see app/build.gradle.kts ::buildOpenSslPerAbi.
# Output layout:
#   app/build/openssl/<abi>/include/openssl/*.h
#   app/build/openssl/<abi>/lib/libssl.a + libcrypto.a
# ----------------------------------------------------------------------

YUME_OPENSSL_ROOT := $(LOCAL_PATH)/../../../../build/openssl/$(TARGET_ARCH_ABI)
YUME_OPENSSL_INC  := $(YUME_OPENSSL_ROOT)/include
YUME_OPENSSL_LIB  := $(YUME_OPENSSL_ROOT)/lib

include $(CLEAR_VARS)
LOCAL_MODULE := openssl_crypto_prebuilt
LOCAL_SRC_FILES := $(YUME_OPENSSL_LIB)/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(YUME_OPENSSL_INC)
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := openssl_ssl_prebuilt
LOCAL_SRC_FILES := $(YUME_OPENSSL_LIB)/libssl.a
LOCAL_EXPORT_C_INCLUDES := $(YUME_OPENSSL_INC)
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := yumetransport
LOCAL_PATH := $(TOP_PATH)
LOCAL_SRC_FILES := ../../cpp/yume_tls_pipe.cpp
LOCAL_C_INCLUDES := $(YUME_OPENSSL_INC)
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden -fvisibility-inlines-hidden -O3 \
    -ffunction-sections -fdata-sections -fstack-protector-strong \
    -Wall -Wextra -Wno-unused-parameter
# Order matters: libssl depends on libcrypto, so the linker must see
# ssl first when resolving symbols.
LOCAL_STATIC_LIBRARIES := openssl_ssl_prebuilt openssl_crypto_prebuilt
LOCAL_LDLIBS := -llog -landroid
LOCAL_LDFLAGS := -Wl,--gc-sections \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384 \
    -Wl,--exclude-libs,ALL
include $(BUILD_SHARED_LIBRARY)
