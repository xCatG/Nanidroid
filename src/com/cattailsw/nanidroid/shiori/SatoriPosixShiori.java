package com.cattailsw.nanidroid.shiori;

import java.nio.charset.Charset;

public class SatoriPosixShiori extends JNIShiori {
    static {
	System.loadLibrary("satoriya");
    }

    private String path = null;

    public SatoriPosixShiori(String p) {
	path = p;

	load(p);
    }

    public String request(String req) {
	String tmp_r = req.substring(0, req.length() - 2) + "Charset: Shift_JIS\r\n\r\n";
	byte[] result;
	try {
	    result = requestFromJNI2( tmp_r.getBytes(Charset.forName("Shift_JIS")) );
	}
	catch(Exception e) {
	    e.printStackTrace();
	    return NanidroidShiori.RES_NO_CONTENT;
	}
	
	return modResponseWithCharSet(result);// super.request( tmp_r );
    }

    public void unloadShiori(){
	unload();
    }

    public native void load(String path);
    public native void unload();
    public native byte[] requestFromJNI2(byte[] req);
}
