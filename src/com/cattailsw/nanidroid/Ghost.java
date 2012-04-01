package com.cattailsw.nanidroid;

import java.util.Hashtable;
import java.util.Map;
import android.util.Log;

public class Ghost {
    private static final String TAG = "Ghost";


    SurfaceManager mgr = null;
    Shiori shiori = null;

    private String rootPath = null;

    private Map<String, String> ghostDesc = null;
    private Map<String, String> shellDesc = null;

    public Ghost(String ghostPath) {
	rootPath = ghostPath;
	mgr = SurfaceManager.getInstance();
	loadGhostInfo();
    }

    private void loadGhostInfo() {
	String master_ghost = rootPath + "/ghost/master";
	String master_ghost_desc = master_ghost + "/descript.txt";
	DescReader ghost_dr = new DescReader(master_ghost_desc);

	String master_shell = rootPath + "/shell/master";
	String master_shell_desc = master_shell + "/descript.txt";
	DescReader shell_dr = new DescReader(master_shell_desc);

	String master_shell_surface = master_shell + "/surfaces.txt";

	try {
	    ghostDesc = ghost_dr.parse();
	    shellDesc = shell_dr.parse();
	}
	catch(Exception e){
	    Log.d(TAG, "desc parsing error");
	    e.printStackTrace();
	}

	SurfaceReader sr = new SurfaceReader( master_shell, master_shell_surface );
    }

}
