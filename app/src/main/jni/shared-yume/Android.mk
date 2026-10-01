# Copyright (C) 2026 FixCraft Inc.

LOCAL_PATH := $(call my-dir)

# libyume.so is cross-compiled directly from the sibling YUME checkout. Its
# protocol, crypto, and packet transport sources are never copied into this app.
YUME_SHARED_ROOT := $(LOCAL_PATH)/../../../../build/yume-shared-abi

include $(CLEAR_VARS)
LOCAL_MODULE := yume_shared
LOCAL_SRC_FILES := $(YUME_SHARED_ROOT)/$(TARGET_ARCH_ABI)/libyume.so
LOCAL_EXPORT_C_INCLUDES := $(YUME_SHARED_ROOT)/include
include $(PREBUILT_SHARED_LIBRARY)

# Narrow Android ownership adapter: opaque handles, packet buffers, and
# VpnService.protect(). All protocol and cryptography remain inside libyume.
include $(CLEAR_VARS)
LOCAL_MODULE := yumeandroid
LOCAL_SRC_FILES := ../../cpp/yume_android_jni.cpp
LOCAL_CPPFLAGS := -std=c++20 -fvisibility=hidden -fvisibility-inlines-hidden -O3 \
    -ffunction-sections -fdata-sections -fstack-protector-strong \
    -Wall -Wextra -Werror
LOCAL_SHARED_LIBRARIES := yume_shared
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,--gc-sections \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
