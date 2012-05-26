package com.cattailsw.nanidroid;

import android.util.Log;

public class InfoOnlyGhost extends Ghost {
    private static final String TAG = "InfoOnlyGhost";
    public InfoOnlyGhost(String path) {
	super(path);
    }

    protected void loadGhostInfo() {
	String master_ghost = rootPath + "/ghost/master";
	String master_ghost_desc = master_ghost + "/descript.txt";
	DescReader ghost_dr = new DescReader(master_ghost_desc);

	String master_shell = rootPath + "/shell/master";
	String master_shell_desc = master_shell + "/descript.txt";
	DescReader shell_dr = new DescReader(master_shell_desc);

	try {
	    ghostDesc = ghost_dr.parse();
	}
	catch(Exception e){
	    Log.d(TAG, "desc parsing error");
	    e.printStackTrace();
	    error = true;
	}
	try {
	    shellDesc = shell_dr.parse();
	}
	catch(Exception e) {
		Log.d(TAG, "shell desc parse error");
		e.printStackTrace();
	}
	
    }

    // do nothing
    public void unload() {}
    @Override
    protected void incrementCreateCount() {} // do nothing
}
