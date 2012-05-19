package com.cattailsw.nanidroid.shiori;

import android.util.Log;
import java.nio.charset.Charset;

public class Kawari extends JNIShiori {
    private static final String TAG = "Kawari";
    static {
	System.loadLibrary("kawari8");
    }


    private String path = null;
    public Kawari(String path) {
	this.path =path;
	load(this.path);
    }

    @Override
    public String request(String req) {
	return modResponseWithCharSet(requestFromJNI(req.getBytes(Charset.forName("Shift_JIS"))));
    }

    @Override
    public void unloadShiori(){
	unload();
    }

    public native void load(String path);
    public native void unload();
    public native byte[] requestFromJNI(byte[] req);
}
