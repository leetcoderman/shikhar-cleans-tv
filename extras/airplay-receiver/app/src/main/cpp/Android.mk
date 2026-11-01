# ndk-build script for the Gupta's TV AirPlay receiver.
# Builds UxPlay's protocol library (GPL-3.0, lib/ is LGPL-2.1+ where marked) with its
# internal mDNS responder, libplist (LGPL-2.1+) and the JNI bridge, against a static
# OpenSSL libcrypto built by scripts/build-openssl.sh.
LOCAL_PATH := $(call my-dir)
UX := uxplay/lib
PL := third_party/libplist

include $(CLEAR_VARS)
LOCAL_MODULE := crypto_static
LOCAL_SRC_FILES := third_party/openssl/lib/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/third_party/openssl/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := plist
LOCAL_SRC_FILES := $(addprefix $(PL)/src/, base64.c bplist.c bytearray.c common.c hashtable.c \
    jplist.c jsmn.c oplist.c out-default.c out-limd.c out-plutil.c plist.c ptrarray.c time64.c xplist.c) \
    $(PL)/libcnary/node.c $(PL)/libcnary/node_list.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(PL) $(LOCAL_PATH)/$(PL)/include $(LOCAL_PATH)/$(PL)/src $(LOCAL_PATH)/$(PL)/libcnary/include
LOCAL_CFLAGS := -DHAVE_CONFIG_H -D__LIBPLIST_INTERNAL -O2 -fvisibility=hidden -Wno-everything
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/$(PL)/include
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := gtvairplay
LOCAL_SRC_FILES := airplay_jni.c \
    $(wildcard $(LOCAL_PATH)/$(UX)/*.c) \
    $(wildcard $(LOCAL_PATH)/$(UX)/playfair/*.c) \
    $(wildcard $(LOCAL_PATH)/$(UX)/llhttp/*.c) \
    $(wildcard $(LOCAL_PATH)/$(UX)/mdnsd/*.c)
LOCAL_SRC_FILES := $(LOCAL_SRC_FILES:$(LOCAL_PATH)/%=%)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(UX) $(LOCAL_PATH)/$(UX)/playfair $(LOCAL_PATH)/$(UX)/llhttp
LOCAL_CFLAGS := -O2 -DNOHOLD -DPLIST_210 -DPLIST_230 -D_REENTRANT -D_LARGEFILE64_SOURCE \
    -DOPENSSL_API_COMPAT=0x10101000L -Wno-deprecated-declarations -Wno-unused-function -Wno-unused-variable
LOCAL_STATIC_LIBRARIES := plist crypto_static
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
