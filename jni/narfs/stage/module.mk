LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := narfs_stage
LOCAL_SRC_FILES := ../narfs_stage.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/..
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := narfs_stage_link_probe
LOCAL_SRC_FILES := ../../../test/native/narfs_stage_link_probe.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/..
LOCAL_CFLAGS := -std=c99 -Wall -Wextra -Werror
LOCAL_STATIC_LIBRARIES := narfs_stage narfs_core narfs_sha256
LOCAL_LDFLAGS := -Wl,--no-undefined
include $(BUILD_EXECUTABLE)
