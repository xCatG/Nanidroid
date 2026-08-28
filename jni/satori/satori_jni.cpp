#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <pthread.h>
#include <string>

#include "satori.h"

extern "C" {
int load(char* data, long dataLength);
int unload();
}

extern Satori gSatori;

extern "C" void satori_ssu_anchor();

namespace {

pthread_mutex_t satoriMutex = PTHREAD_MUTEX_INITIALIZER;
bool satoriLoaded = false;

class SatoriLock {
public:
    SatoriLock() { pthread_mutex_lock(&satoriMutex); }
    ~SatoriLock() { pthread_mutex_unlock(&satoriMutex); }
private:
    SatoriLock(const SatoriLock&);
    SatoriLock& operator=(const SatoriLock&);
};

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != NULL) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}

jint nativeLoad(JNIEnv* env, jobject, jstring path, jstring cacheDirectory) {
    if (path == NULL || cacheDirectory == NULL) {
        return 0;
    }
    SatoriLock lock;
    if (satoriLoaded) return -1;
    // SSU is a linked Android DSO; its soname is resolved by the loader.
    (void)cacheDirectory;

    const char* utf8Path = env->GetStringUTFChars(path, nullptr);
    if (utf8Path == NULL) return 0;
    const jsize length = env->GetStringUTFLength(path);
    char* copy = static_cast<char*>(std::malloc(static_cast<size_t>(length) + 1));
    if (copy == NULL) {
        env->ReleaseStringUTFChars(path, utf8Path);
        return 0;
    }
    std::memcpy(copy, utf8Path, static_cast<size_t>(length));
    copy[length] = '\0';
    env->ReleaseStringUTFChars(path, utf8Path);

    gSatori.configure_posix_saori_fallback("");
    if (!load(copy, length)) {
        // load owns and frees copy on every path.
        if (unload()) {
            satoriLoaded = false;
            return 0;
        }
        satoriLoaded = true;
        return -2;
    }
    satoriLoaded = true;
    return 1;
}

jbyteArray nativeRequest(JNIEnv* env, jobject, jbyteArray request) {
    if (request == NULL) {
        throwIllegalState(env, "Satori request must not be null");
        return NULL;
    }

    const jsize length = env->GetArrayLength(request);
    std::string input(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(request, 0, length,
            reinterpret_cast<jbyte*>(&input[0]));
        if (env->ExceptionCheck()) return NULL;
    }

    SatoriLock lock;
    if (!satoriLoaded) {
        throwIllegalState(env, "Satori is not loaded");
        return NULL;
    }
    const std::string response =
        static_cast<SakuraDLLHost*>(&gSatori)->request(input);
    jbyteArray output = env->NewByteArray(static_cast<jsize>(response.size()));
    if (output != NULL && !response.empty()) {
        env->SetByteArrayRegion(output, 0, static_cast<jsize>(response.size()),
            reinterpret_cast<const jbyte*>(response.data()));
    }
    return output;
}

jboolean nativeUnload(JNIEnv*, jobject) {
    SatoriLock lock;
    if (!satoriLoaded) return JNI_TRUE;
    if (!unload()) return JNI_FALSE;
    satoriLoaded = false;
    return JNI_TRUE;
}

JNINativeMethod methods[] = {
    {const_cast<char*>("nativeLoad"), const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;)I"),
        reinterpret_cast<void*>(nativeLoad)},
    {const_cast<char*>("nativeRequest"), const_cast<char*>("([B)[B"),
        reinterpret_cast<void*>(nativeRequest)},
    {const_cast<char*>("nativeUnload"), const_cast<char*>("()Z"),
        reinterpret_cast<void*>(nativeUnload)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    satori_ssu_anchor();
    JNIEnv* env = NULL;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/cattailsw/nanidroid/shiori/SatoriShiori");
    if (clazz == NULL || env->RegisterNatives(
            clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    env->DeleteLocalRef(clazz);
    return JNI_VERSION_1_6;
}
