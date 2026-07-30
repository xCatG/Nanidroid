#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <string>

#include "aya5.h"

namespace {
pthread_mutex_t gYayaMutex = PTHREAD_MUTEX_INITIALIZER;
bool gYayaLoaded = false;

class YayaLock {
public:
    YayaLock() { pthread_mutex_lock(&gYayaMutex); }
    ~YayaLock() { pthread_mutex_unlock(&gYayaMutex); }
};

void nativeLoad(JNIEnv* env, jobject, jstring path) {
    if (path == NULL) return;
    YayaLock lock;
    if (gYayaLoaded) {
        unload();
        gYayaLoaded = false;
    }
    const char* value = env->GetStringUTFChars(path, NULL);
    if (value == NULL) return;
    const jsize length = env->GetStringUTFLength(path);
    const std::string directory(value, length);
    char* copy = static_cast<char*>(malloc(static_cast<size_t>(length) + 1));
    if (copy != NULL) {
        memcpy(copy, value, length);
        copy[length] = '\0';
    }
    env->ReleaseStringUTFChars(path, value);
    const int loaded = copy != NULL ? load(copy, length) : 0;
    gYayaLoaded = loaded != 0;
    __android_log_print(ANDROID_LOG_INFO, "YayaJNI", "load(%s) = %d", directory.c_str(), loaded);
    // YAYA's POSIX load consumes and frees `copy`.
}

jstring nativeTransportCharset(JNIEnv* env, jobject) {
    YayaLock lock;
    const char* charset = gYayaLoaded ? yaya_output_charset() : "UTF-8";
    return env->NewStringUTF(charset == NULL ? "UTF-8" : charset);
}

jbyteArray nativeRequest(JNIEnv* env, jobject, jbyteArray request) {
    if (request == NULL) return env->NewByteArray(0);
    YayaLock lock;
    if (!gYayaLoaded) return env->NewByteArray(0);
    const jsize length = env->GetArrayLength(request);
    char* input = static_cast<char*>(malloc(static_cast<size_t>(length) + 1));
    if (input == NULL) return env->NewByteArray(0);
    env->GetByteArrayRegion(request, 0, length, reinterpret_cast<jbyte*>(input));
    input[length] = '\0';
    long resultLength = length;
    char* result = ::request(input, &resultLength);
    // YAYA borrows input on its successful POSIX request path.  Do not free it
    // until after the response has been copied.  POSIX YAYA leaves ownership
    // with this bridge on every path.
    __android_log_print(ANDROID_LOG_INFO, "YayaJNI", "request bytes=%d response=%ld", length, resultLength);
    if (result == NULL || resultLength <= 0) {
        free(result);
        free(input);
        return env->NewByteArray(0);
    }
    jbyteArray response = env->NewByteArray(static_cast<jsize>(resultLength));
    if (response != NULL) env->SetByteArrayRegion(response, 0, static_cast<jsize>(resultLength), reinterpret_cast<const jbyte*>(result));
    free(result);
    free(input);
    return response;
}

void nativeUnload(JNIEnv*, jobject) {
    YayaLock lock;
    if (gYayaLoaded) {
        unload();
        gYayaLoaded = false;
    }
}
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = NULL;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass type = env->FindClass("com/cattailsw/nanidroid/shiori/YayaShiori");
    if (type == NULL) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {const_cast<char*>("nativeLoad"), const_cast<char*>("(Ljava/lang/String;)V"), reinterpret_cast<void*>(nativeLoad)},
        {const_cast<char*>("nativeTransportCharset"), const_cast<char*>("()Ljava/lang/String;"), reinterpret_cast<void*>(nativeTransportCharset)},
        {const_cast<char*>("nativeRequest"), const_cast<char*>("([B)[B"), reinterpret_cast<void*>(nativeRequest)},
        {const_cast<char*>("nativeUnload"), const_cast<char*>("()V"), reinterpret_cast<void*>(nativeUnload)},
    };
    return env->RegisterNatives(type, methods, 4) == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
