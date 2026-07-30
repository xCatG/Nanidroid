#include "android_charset.h"
#include "manifest.h"

#if defined(__ANDROID__)

#include <climits>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {
JavaVM* gVm = NULL;
jclass gCharsetClass = NULL;
jclass gStringClass = NULL;
jmethodID gCharsetForName = NULL;
jmethodID gStringFromBytes = NULL;
jmethodID gStringGetBytes = NULL;

const char* charset_name(int charset) {
    switch (charset) {
    case CHARSET_SJIS: return "Shift_JIS";
    case CHARSET_EUCJP: return "EUC-JP";
    case CHARSET_JIS: return "ISO-2022-JP";
    case CHARSET_BIG5: return "Big5";
    case CHARSET_GB2312: return "GB2312";
    case CHARSET_EUCKR: return "EUC-KR";
    case CHARSET_UTF8:
    case CHARSET_DEFAULT:
    default: return "UTF-8";
    }
}

bool clear_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

class ScopedEnv {
public:
    ScopedEnv() : env_(NULL), attached_(false) {
        if (gVm == NULL) return;
        if (gVm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (gVm->AttachCurrentThread(&env_, NULL) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = NULL;
            }
        }
    }

    ~ScopedEnv() {
        if (attached_) gVm->DetachCurrentThread();
    }

    JNIEnv* get() const { return env_; }

private:
    JNIEnv* env_;
    bool attached_;
};

jobject charset_for(JNIEnv* env, int charset) {
    jstring name = env->NewStringUTF(charset_name(charset));
    if (name == NULL || clear_exception(env)) return NULL;
    jobject result = env->CallStaticObjectMethod(gCharsetClass, gCharsetForName, name);
    env->DeleteLocalRef(name);
    return clear_exception(env) ? NULL : result;
}

void append_code_point(std::vector<jchar>& output, unsigned int value) {
    if (value <= 0xffff) {
        output.push_back(static_cast<jchar>(value));
    } else if (value <= 0x10ffff) {
        value -= 0x10000;
        output.push_back(static_cast<jchar>(0xd800 + (value >> 10)));
        output.push_back(static_cast<jchar>(0xdc00 + (value & 0x3ff)));
    } else {
        output.push_back(static_cast<jchar>(0xfffd));
    }
}
}

bool android_charset_initialize(JNIEnv* env) {
    if (gVm != NULL) return true;
    if (env->GetJavaVM(&gVm) != JNI_OK) return false;

    jclass charset = env->FindClass("java/nio/charset/Charset");
    jclass string = env->FindClass("java/lang/String");
    if (charset == NULL || string == NULL) {
        clear_exception(env);
        return false;
    }

    gCharsetClass = static_cast<jclass>(env->NewGlobalRef(charset));
    gStringClass = static_cast<jclass>(env->NewGlobalRef(string));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string);
    if (gCharsetClass == NULL || gStringClass == NULL) return false;

    gCharsetForName = env->GetStaticMethodID(gCharsetClass, "forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;");
    gStringFromBytes = env->GetMethodID(gStringClass, "<init>", "([BLjava/nio/charset/Charset;)V");
    gStringGetBytes = env->GetMethodID(gStringClass, "getBytes", "(Ljava/nio/charset/Charset;)[B");
    return gCharsetForName != NULL && gStringFromBytes != NULL && gStringGetBytes != NULL && !clear_exception(env);
}

char* android_utf16_to_charset(const yaya::char_t* input, int charset) {
    if (input == NULL || gVm == NULL) return NULL;

    ScopedEnv scoped;
    JNIEnv* env = scoped.get();
    if (env == NULL) return NULL;

    std::vector<jchar> units;
    for (const yaya::char_t* current = input; *current; ++current) {
        append_code_point(units, static_cast<unsigned int>(*current));
    }
    jstring text = env->NewString(units.empty() ? NULL : &units[0], static_cast<jsize>(units.size()));
    if (text == NULL || clear_exception(env)) return NULL;

    jobject converter = charset_for(env, charset);
    if (converter == NULL) {
        env->DeleteLocalRef(text);
        return NULL;
    }
    jbyteArray bytes = static_cast<jbyteArray>(env->CallObjectMethod(text, gStringGetBytes, converter));
    env->DeleteLocalRef(converter);
    env->DeleteLocalRef(text);
    if (bytes == NULL || clear_exception(env)) return NULL;

    const jsize length = env->GetArrayLength(bytes);
    char* output = static_cast<char*>(malloc(static_cast<size_t>(length) + 1));
    if (output != NULL) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(output));
        output[length] = '\0';
        if (clear_exception(env)) {
            free(output);
            output = NULL;
        }
    }
    env->DeleteLocalRef(bytes);
    return output;
}

yaya::char_t* android_charset_to_utf16(const char* input, int charset) {
    if (input == NULL || gVm == NULL) return NULL;

    ScopedEnv scoped;
    JNIEnv* env = scoped.get();
    if (env == NULL) return NULL;

    const jsize length = static_cast<jsize>(strlen(input));
    jbyteArray bytes = env->NewByteArray(length);
    if (bytes == NULL || clear_exception(env)) return NULL;
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(input));
    if (clear_exception(env)) {
        env->DeleteLocalRef(bytes);
        return NULL;
    }

    jobject converter = charset_for(env, charset);
    if (converter == NULL) {
        env->DeleteLocalRef(bytes);
        return NULL;
    }
    jstring text = static_cast<jstring>(env->NewObject(gStringClass, gStringFromBytes, bytes, converter));
    env->DeleteLocalRef(converter);
    env->DeleteLocalRef(bytes);
    if (text == NULL || clear_exception(env)) return NULL;

    const jsize length16 = env->GetStringLength(text);
    const jchar* chars = env->GetStringChars(text, NULL);
    if (chars == NULL || clear_exception(env)) {
        env->DeleteLocalRef(text);
        return NULL;
    }

    std::vector<yaya::char_t> output;
    for (jsize i = 0; i < length16; ++i) {
        const unsigned int value = chars[i];
#if WCHAR_MAX > 0xffff
        if (value >= 0xd800 && value <= 0xdbff && i + 1 < length16 &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            output.push_back(static_cast<yaya::char_t>(0x10000 + ((value - 0xd800) << 10) + (chars[++i] - 0xdc00)));
        } else {
            output.push_back(static_cast<yaya::char_t>(value));
        }
#else
        output.push_back(static_cast<yaya::char_t>(value));
#endif
    }
    env->ReleaseStringChars(text, chars);
    env->DeleteLocalRef(text);

    yaya::char_t* result = static_cast<yaya::char_t*>(malloc((output.size() + 1) * sizeof(yaya::char_t)));
    if (result == NULL) return NULL;
    if (!output.empty()) memcpy(result, &output[0], output.size() * sizeof(yaya::char_t));
    result[output.size()] = 0;
    return result;
}

#endif
