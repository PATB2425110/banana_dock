LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := cardockbar
LOCAL_SRC_FILES := native_crash.c
# -funwind-tables 是 Android 默认开启的，显式写上防止被优化掉
LOCAL_CFLAGS    := -funwind-tables -fno-omit-frame-pointer -O1
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
