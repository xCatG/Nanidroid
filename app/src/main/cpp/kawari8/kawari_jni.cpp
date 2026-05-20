#include <string>
#include <jni.h>

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
  JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_load(JNIEnv *env, jobject thiz, jstring path);
  JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_unload(JNIEnv *env, jobject thiz);

  SO_HANDLE h = 0;
}

static string make_utf8_string_from_jstring(JNIEnv *env, jstring jstr) {
    const char* chars = env->GetStringUTFChars(jstr, NULL);
    string str(chars, env->GetStringUTFLength(jstr));
    env->ReleaseStringUTFChars(jstr, chars);
    return str;
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
  string resstr = TKawariShioriFactory::GetFactory().RequestInstance((int)h, 
								     make_string_from_jbyteArray(env, req));
  return make_jbyteArray_from_string(env, resstr);
}


JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_load(JNIEnv *env, jobject thiz, jstring path){
  h = TKawariShioriFactory::GetFactory().CreateInstance( make_utf8_string_from_jstring(env, path) );
}

JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_unload(JNIEnv *env, jobject thiz){
  TKawariShioriFactory::GetFactory().DisposeInstance((int)h);
}
