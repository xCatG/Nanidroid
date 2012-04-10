package com.cattailsw.nanidroid;

/* shiori-jni function calls should be named in the style of
   jstring Java_com_cattailsw_nanidroid_getModuleNameFromJNI(JNIEnv *env, jobject thiz)
   jstring Java_com_cattailsw_nanidroid_requestFromJNI(JNIEnv *env, jobject thiz, jstring req)
   void Java_com_cattailsw_nanidroid_terminateFromJNI(JNIEnv *env, jobject thiz)
*/

public abstract class JNIShiori implements Shiori {
    

    public String getModuleName(){
	return getModuleNameFromJNI();
    }

    public String request(String req) {
	return requestFromJNI(req);
    }

    public void terminate(){
	terminateFromJNI();
    }

    public native String getModuleNameFromJNI();
    public native String requestFromJNI(String req);
    public native void terminateFromJNI();

}
