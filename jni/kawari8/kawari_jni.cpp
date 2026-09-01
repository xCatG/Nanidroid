#include <string>
#include <jni.h>
#include <pthread.h>

#include <android/log.h>
#define  LOG_TAG    "libgl2jni"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)

#include "config.h"
#include "include/shiori_object.h"
#include "shiori/kawari_shiori.h"

using namespace std;

extern "C" {

  JNIEXPORT jbyteArray JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_requestFromJNI(JNIEnv *env, jobject thiz, jbyteArray req);
  JNIEXPORT jint JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_nativeLoad(JNIEnv *env, jobject thiz, jstring path);
  JNIEXPORT jboolean JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_nativeUnload(JNIEnv *env, jobject thiz);

  SO_HANDLE h = 0;
}

static pthread_mutex_t kawari_mutex = PTHREAD_MUTEX_INITIALIZER;

class KawariLock {
public:
  KawariLock() { pthread_mutex_lock(&kawari_mutex); }
  ~KawariLock() { pthread_mutex_unlock(&kawari_mutex); }
};

static bool make_utf8_string_from_jstring(JNIEnv *env, jstring jstr, string *out) {
    if (jstr == NULL) return false;
    const char* chars = env->GetStringUTFChars(jstr, NULL);
    if (chars == NULL) return false;
    try {
      out->assign(chars, env->GetStringUTFLength(jstr));
    } catch (...) {
      env->ReleaseStringUTFChars(jstr, chars);
      return false;
    }
    env->ReleaseStringUTFChars(jstr, chars);
    return !env->ExceptionCheck();
}

static void throwIllegalState(JNIEnv *env, const char *message) {
  jclass exception = env->FindClass("java/lang/IllegalStateException");
  if (exception != NULL) {
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
  }
}

static void printString(const char *name){
  LOGI("[%s]%s\n", LOG_TAG, name);
}

static jbyteArray make_jbyteArray_from_string(JNIEnv *env, const string& str) {
    long len = str.length();
    jbyteArray jbytes = env->NewByteArray(len);
    env->SetByteArrayRegion(
	jbytes, 0, len, const_cast<jbyte*>(
	    reinterpret_cast<const jbyte*>(str.c_str())));
    return jbytes;
}

static string make_string_from_jbyteArray(JNIEnv *env, jbyteArray jbytes) {
    char *bytes = reinterpret_cast<char*>(
	env->GetByteArrayElements(jbytes, NULL));
    long len = env->GetArrayLength(jbytes);
    string str(bytes, len);
    env->ReleaseByteArrayElements(
	jbytes, reinterpret_cast<jbyte*>(bytes), JNI_ABORT);
    return str;
}


JNIEXPORT jbyteArray JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_requestFromJNI(JNIEnv *env, jobject thiz, jbyteArray req){
  KawariLock lock;
  if (h == 0) {
    throwIllegalState(env, "Kawari 8 is not loaded");
    return NULL;
  }
  string resstr = TKawariShioriFactory::GetFactory().RequestInstance((int)h, 
								     make_string_from_jbyteArray(env, req));
  return make_jbyteArray_from_string(env, resstr);
}


JNIEXPORT jint JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_nativeLoad(JNIEnv *env, jobject thiz, jstring path){
  KawariLock lock;
  if (h != 0) {
    return -1;
  }
  string directory;
  if (!make_utf8_string_from_jstring(env, path, &directory)) return 0;
  h = TKawariShioriFactory::GetFactory().CreateInstance(directory);
  return h != 0 ? 1 : 0;
}

JNIEXPORT jboolean JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_nativeUnload(JNIEnv *env, jobject thiz){
  KawariLock lock;
  if (h == 0) return JNI_TRUE;
  if (!TKawariShioriFactory::GetFactory().DisposeInstance((int)h)) return JNI_FALSE;
  h = 0;
  return JNI_TRUE;
}
