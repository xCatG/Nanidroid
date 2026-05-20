#include <string>
#include <jni.h>

#include <android/log.h>

//#include "SakuraClient.h"
#include "satori.h"

#define  LOG_TAG    "libgl2jni"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)


extern "C" {

  JNIEXPORT jbyteArray JNICALL Java_com_cattailsw_nanidroid_shiori_JNIShiori_requestFromJNI(JNIEnv *env, jobject thiz, jstring req);

  JNIEXPORT jbyteArray JNICALL Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_requestFromJNI2(JNIEnv *env, jobject thiz, jbyteArray req);

  JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_load(JNIEnv *env, jobject thiz, jstring path);
  JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_unload(JNIEnv *env, jobject thiz);

  int load(char* i_data, long i_data_len);
  int unload(void);
  char* request(char* i_data, long* io_data_len);
}

extern Satori gSatori;

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


JNIEXPORT jbyteArray JNICALL Java_com_cattailsw_nanidroid_shiori_JNIShiori_requestFromJNI(JNIEnv *env, jobject thiz, jstring req){
  string sRequest = make_utf8_string_from_jstring( env, req );
  string s = ((SakuraDLLHost*)&gSatori)->request( sRequest );
  //printString( s.c_str() ); 
  return make_jbyteArray_from_string(env, s);
}

JNIEXPORT jbyteArray JNICALL Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_requestFromJNI2(JNIEnv *env, jobject thiz, jbyteArray req) {
  string sReq = make_string_from_jbyteArray(env, req);
  string s = ((SakuraDLLHost*)&gSatori)->request( sReq );
  return make_jbyteArray_from_string(env, s);
}



JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_load(JNIEnv *env, jobject thiz, jstring path) {
	setenv("SAORI_FALLBACK_ALWAYS", "value", 1);
  string sPath = make_utf8_string_from_jstring( env, path );
  
  long pLength = sPath.size();
  char* pPath = (char*)malloc(pLength + 1);
  strcpy(pPath, sPath.c_str());
  //(char*)sPath.c_str();
  load(pPath, pLength);
}

JNIEXPORT void JNICALL Java_com_cattailsw_nanidroid_shiori_SatoriPosixShiori_unload(JNIEnv *env, jobject thiz){
	unload();
}
