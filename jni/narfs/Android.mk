LOCAL_PATH := $(call my-dir)

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
