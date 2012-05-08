package com.cattailsw.nanidroid.shiori;

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
	return modResponseWithCharSet(requestFromJNI(req));
    }

    public void terminate(){
	terminateFromJNI();
    }


    protected String modResponseWithCharSet(byte[] bytes) {
	String s = new String(bytes);
	int pos_charset = s.indexOf("Charset:");
	if ( pos_charset == -1 ) {
	    return s;
	}
	else {
	    int pos_crlf = s.indexOf("\r\n", pos_charset);
	    String charset = s.substring(pos_charset + 8, pos_crlf).trim();
	    try {
	    return new String(bytes, charset);
	    }
	    catch(Exception e){
		return s;
	    }
	}
    }

    public native String getModuleNameFromJNI();
    public native byte[] requestFromJNI(String req);
    public native void terminateFromJNI();

}
