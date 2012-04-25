package com.cattailsw.nanidroid;

public class SatoriPosixShiori extends JNIShiori {
    static {
	System.loadLibrary("satoriya");
    }

    private String path = null;

    public SatoriPosixShiori(String p) {
	path = p;

	load(p);
    }

    public void unloadShiori(){
	unload();
    }

    public native void load(String path);
    public native void unload();

}
