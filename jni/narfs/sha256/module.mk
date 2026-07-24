LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := narfs_sha256
LOCAL_SRC_FILES := ../narfs_sha256.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/..
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := narfs_sha256_link_probe
LOCAL_SRC_FILES := ../../../test/native/narfs_sha256_link_probe.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/..
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror
LOCAL_STATIC_LIBRARIES := narfs_sha256
LOCAL_LDFLAGS := -Wl,--no-undefined
include $(BUILD_EXECUTABLE)
