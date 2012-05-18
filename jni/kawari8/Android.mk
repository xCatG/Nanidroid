LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := kawari8

KISSRC =   kis/kis_echo.cpp \
           kis/kis_dict.cpp \
           kis/kis_date.cpp \
           kis/kis_counter.cpp \
           kis/kis_file.cpp \
           kis/kis_escape.cpp \
           kis/kis_urllist.cpp \
           kis/kis_substitute.cpp \
           kis/kis_split.cpp \
           kis/kis_communicate.cpp \
           kis/kis_xargs.cpp \
           kis/kis_string.cpp \
           kis/kis_help.cpp \
           kis/kis_saori.cpp \
           kis/kis_system.cpp

CRYPTSRC = libkawari/kawari_crypt.cpp \
           misc/base64.cpp

CORESRC =  libkawari/kawari_engine.cpp \
           libkawari/kawari_ns.cpp \
           libkawari/kawari_dict.cpp \
           libkawari/kawari_code.cpp \
           libkawari/kawari_codeset.cpp \
           libkawari/kawari_codeexpr.cpp \
           libkawari/kawari_codekis.cpp \
           libkawari/kawari_vm.cpp \
           libkawari/kawari_lexer.cpp \
           libkawari/kawari_compiler.cpp \
           libkawari/kawari_log.cpp \
           libkawari/kawari_rc.cpp \
           misc/misc.cpp \
           misc/mt19937ar.cpp \
           misc/l10n.cpp \
           misc/phttp.cpp \
           saori/saori.cpp \
           saori/saori_module.cpp \
           saori/saori_unique.cpp \
           $(KISSRC) \
           $(CRYPTSRC)

SHIOSRC =  shiori/kawari_shiori.cpp \
           shiori/shiori_object.cpp \
           shiori/shiori.cpp

LOCAL_SRC_FILES = $(CORESRC) \
		  $(SHIOSRC) \
		  kawari_jni.cpp

LOCAL_CPP_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/include $(LOCAL_PATH)/libkawari $(LOCAL_PATH)/kis $(LOCAL_PATH)/misc $(LOCAL_PATH)/saori $(LOCAL_PATH)/shiori
LOCAL_CPPFLAGS := -DPOSIX -DNDEBUG -Wno-write-strings
LOCAL_LDLIBS := -llog -ldl

include $(BUILD_SHARED_LIBRARY)

