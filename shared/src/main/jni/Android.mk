LOCAL_PATH := $(call my-dir)

# Iteration A must not package or load the iteration B crypto module.
ifeq ($(GEELY_NATIVE_MEDIA),1)
include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_chacha
LOCAL_SRC_FILES := chacha20_poly1305.c chacha20_poly1305_jni.c
LOCAL_CFLAGS := -O2 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := legacy_usb_compat
LOCAL_SRC_FILES := legacy_usb_jni.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
