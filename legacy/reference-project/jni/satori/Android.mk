LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := satoriya
LOCAL_SRC_FILES = \
	../_/Sender.cpp \
	../_/Utilities.cpp \
	../_/calc.cpp \
	../_/stltool.cpp \
	SakuraCS.cpp \
	SakuraClient.cpp \
	SakuraDLLClient.cpp \
	SakuraDLLHost.cpp \
	SaoriClient.cpp \
	satori.cpp \
	satoriTranslate.cpp \
	satori_AnalyzeRequest.cpp \
	satori_CreateResponce.cpp \
	satori_EventOperation.cpp \
	satori_Kakko.cpp \
	satori_load_dict.cpp \
	satori_load_unload.cpp \
	satori_sentence.cpp \
	satori_tool.cpp \
	shiori_plugin.cpp \
	satori_jni.cpp

#CXXFLAGS_ADD = -DPOSIX -DSATORI_DLL -I. -I../_ -fpermissive
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/../_ 
LOCAL_CPPFLAGS := -DPOSIX -DSATORI_DLL -g -ggdb -O0 #-fexceptions
LOCAL_LDLIBS := -llog -ldl

include $(BUILD_SHARED_LIBRARY)
