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

# libyume.so is cross-compiled directly from the signed sibling YUME checkout.
# Its crypto and wire sources are never copied into this Android project.
YUME_SHARED_ROOT := $(LOCAL_PATH)/../../../../build/yume-shared-abi

include $(CLEAR_VARS)
LOCAL_MODULE := yume_shared
LOCAL_SRC_FILES := $(YUME_SHARED_ROOT)/$(TARGET_ARCH_ABI)/libyume.so
LOCAL_EXPORT_C_INCLUDES := $(YUME_SHARED_ROOT)/include
include $(PREBUILT_SHARED_LIBRARY)

# Narrow Android ownership adapter: opaque handles, packet buffers, and
# VpnService.protect(). All protocol/crypto remains inside libyume.
include $(CLEAR_VARS)
LOCAL_MODULE := yumeandroid
LOCAL_PATH := $(TOP_PATH)
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
