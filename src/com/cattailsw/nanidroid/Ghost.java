package com.cattailsw.nanidroid;

import java.util.Hashtable;
import java.util.Map;
import android.util.Log;
import java.io.File;

public class Ghost {
    private static final String TAG = "Ghost";


    SurfaceManager mgr = null;
    Shiori shiori = null;

    protected String rootPath = null;
    protected String ghostDirName = null;
    protected Map<String, String> ghostDesc = null;
    protected Map<String, String> shellDesc = null;

    protected boolean error = false;

    public Ghost(String ghostPath) {
	rootPath = ghostPath;
	ghostDirName = (new File(ghostPath)).getName();
	Log.d(TAG, "gdname="+ghostDirName);
	mgr = SurfaceManager.getInstance();
	loadGhostInfo();
    }

    public boolean ghostError(){
	return error;
    }

    protected void loadGhostInfo() {


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
	    error = true;
	}

	SurfaceReader sr = new SurfaceReader( master_shell, master_shell_surface );
	if ( error == false )
	    error = sr.error;
    }

    public String getGhostDirName() {
	return ghostDirName;
    }

    public String getGhostPath(){
	return rootPath;
    }

    public String getGhostName() {
	return ghostDesc.get("name");
    }

    public String getCrafterName() {
	if ( ghostDesc.get("craftmanw") != null )
	    return ghostDesc.get("craftmanw");

	return ghostDesc.get("craftman");
    }

    public String getSakuraName() {
	return ghostDesc.get("sakura.name");
    }

    public String getKeroName(){
	return ghostDesc.get("kero.name");
    }

    public String getUsername() {
	return "User";
    }

}
