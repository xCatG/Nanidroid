package com.cattailsw.nanidroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.util.Map;

import android.content.Context;
import android.util.Log;

import com.cattailsw.nanidroid.shiori.Shiori;

public class Ghost {
    private static final String TAG = "Ghost";


    SurfaceManager mgr = null;
    Shiori shiori = null;

    protected String rootPath = null;
    protected String ghostDirName = null;
    protected Map<String, String> ghostDesc = null;
    protected Map<String, String> shellDesc = null;

    protected boolean error = false;

    protected Context mCtx;

    public Ghost(String ghostPath, Context ctx) {
	rootPath = ghostPath;
	ghostDirName = (new File(ghostPath)).getName();
	Log.d(TAG, "gdname="+ghostDirName);
	mgr = new SurfaceManager(ghostDirName);//SurfaceManager.getInstance();
	mCtx = ctx;
	loadGhostInfo();
    }

    public Ghost(String ghostPath) {
	this(ghostPath, null);
    }

    public boolean ghostError(){
	return error;
    }

    protected void loadGhostInfo() {


	String master_ghost = rootPath + "/ghost/master/";
	String master_ghost_desc = master_ghost + "descript.txt";
	DescReader ghost_dr = new DescReader(master_ghost_desc);

	String master_shell = rootPath + "/shell/master/";
	String master_shell_desc = master_shell + "descript.txt";
	DescReader shell_dr = new DescReader(master_shell_desc);

	String master_shell_surface = master_shell + "surfaces.txt";

	try {
	    ghostDesc = ghost_dr.parse();
	    shellDesc = shell_dr.parse();
	}
	catch(Exception e){
	    Log.d(TAG, "desc parsing error");
	    e.printStackTrace();
	    error = true;
	    return;
	}

	SurfaceReader sr = new SurfaceReader(mgr, master_shell, master_shell_surface );
	if ( error == false )
	    error = sr.error;

	shiori = ShioriFactory.getInstance().getShiori( master_ghost, ghostDesc, mCtx );
    }

    public void unload() {
	shiori.unloadShiori();
    }

    // because GhostMgr use ghost dir as its id, just return ghostDir here
    public String getGhostId() {
	return ghostDirName; 
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

    public String getShellName() {
	return shellDesc.get("name");
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

    public ShioriResponse sendOnSecondChange(int hour){
	return doShioriEvent("OnSecondChange", new String[]{"" + hour, "0", "0", "1"});
    }

    public ShioriResponse sendOnMinuteChange(int hour){
	return doShioriEvent("OnMinuteChange", new String[]{"" + hour, "0", "0", "1"});
    }

    // can return null
    public String getStringFromShiori(String id) {
	if (shiori == null )
	    return null;

	ShioriResponse r = doShioriEvent(id, null);
	if ( r.getStatusCode() != 200 )
	    return null;
	
	return r.getKey("Value");
    }

    public ShioriResponse doShioriEvent(String event, String[] ref) {
	if ( shiori == null ) {
	    return new ShioriResponse("SHIORI/2.0 500 Internal Server Error");
	}

	StringBuffer sb = new StringBuffer();

	sb.append("GET SHIORI/3.0\r\nSender: "+ Setup.NANIDROID + "\r\n");
	sb.append("ID: " + event + "\r\n");
	sb.append("SecurityLevel: local\r\n");
	if ( ref != null ) {
	    for ( int i = 0; i < ref.length; i++ ){
		sb.append("Reference"+i+": " + ref[i] + "\r\n");
	    }
	}

	sb.append("\r\n");

	BufferedReader br = new BufferedReader( new StringReader( shiori.request( sb.toString() )));
	ShioriResponse res = new ShioriResponse(br);
	try {
	    br.close();
	}
	catch(Exception e){

	}

	return res;
    }

}
