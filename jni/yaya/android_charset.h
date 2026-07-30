#ifndef YAYA_ANDROID_CHARSET_H
#define YAYA_ANDROID_CHARSET_H

#if defined(__ANDROID__)
#include <jni.h>

#include "globaldef.h"

bool android_charset_initialize(JNIEnv* env);
char* android_utf16_to_charset(const yaya::char_t* input, int charset);
yaya::char_t* android_charset_to_utf16(const char* input, int charset);
#endif

#endif
