LOCAL_PATH := $(call my-dir)
NARFS_LOCAL_PATH := $(LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE := narfs_core
LOCAL_SRC_FILES := narfs_core.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := narfs_core_link_probe
LOCAL_SRC_FILES := ../../test/native/narfs_link_probe.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror
LOCAL_STATIC_LIBRARIES := narfs_core
LOCAL_LDFLAGS := -Wl,--no-undefined
include $(BUILD_EXECUTABLE)

ifeq ($(NANIDROID_NARFS_JNI_CANDIDATE),1)
ifeq ($(NANIDROID_NARFS_FULL_JNI_CANDIDATE),1)
$(error full and inspector JNI candidates are exclusive)
endif
endif

ifeq ($(NANIDROID_NARFS_JNI_CANDIDATE),1)
include $(CLEAR_VARS)
LOCAL_MODULE := narfs
LOCAL_SRC_FILES := narfs_jni.c narfs_utf.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_STATIC_LIBRARIES := narfs_core
LOCAL_LDFLAGS := -Wl,--as-needed -Wl,--no-undefined -Wl,--version-script,$(LOCAL_PATH)/narfs_jni.map
include $(BUILD_SHARED_LIBRARY)
endif

ifeq ($(NANIDROID_NARFS_SHA256_CANDIDATE),1)
include $(LOCAL_PATH)/sha256/module.mk
endif

LOCAL_PATH := $(NARFS_LOCAL_PATH)
ifeq ($(NANIDROID_NARFS_STAGE_CANDIDATE),1)
include $(LOCAL_PATH)/stage/module.mk
endif

LOCAL_PATH := $(NARFS_LOCAL_PATH)
ifeq ($(NANIDROID_NARFS_FULL_JNI_CANDIDATE),1)
ifneq ($(NANIDROID_NARFS_STAGE_CANDIDATE),1)
$(error full JNI candidate requires staging candidate)
endif
ifneq ($(NANIDROID_NARFS_SHA256_CANDIDATE),1)
$(error full JNI candidate requires SHA-256 candidate)
endif
include $(CLEAR_VARS)
LOCAL_MODULE := narfs_full
LOCAL_MODULE_FILENAME := libnarfs
LOCAL_SRC_FILES := narfs_jni.c narfs_stage_jni.c narfs_utf.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror -fvisibility=hidden
LOCAL_STATIC_LIBRARIES := narfs_stage narfs_core narfs_sha256
LOCAL_LDFLAGS := -Wl,--as-needed -Wl,--no-undefined -Wl,--version-script,$(LOCAL_PATH)/narfs_full_jni.map
include $(BUILD_SHARED_LIBRARY)
endif
