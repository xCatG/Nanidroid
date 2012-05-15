package com.cattailsw.nanidroid.shiori;

import java.util.HashSet;
import java.util.Hashtable;

import android.content.Context;
import java.util.Locale;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.io.InputStream;
import android.util.Log;

public class NanidroidShiori extends EchoShiori {
    private static final String TAG = "NanidroidShiori";
    HashSet<String> igTable = new HashSet<String>();
    public static final String RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT";
    private static final String RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: "+ TAG +"\r\nValue: ";
    private static final String RES_END = "\r\nCharset: UTF-8\r\n";

    private static final String CONTENT_FILE_NAME = "content.txt";


    private Hashtable<String, String> evtTable = null;

    String header = null;
    private Context mCtx = null;
    private String rootpath = null;
    //    Hashtable<String, String> reqTable = null;
    
    public NanidroidShiori() {

    }

    public NanidroidShiori(Context ctx, String path) {
	mCtx = ctx;
	rootpath = path;

	// scan and get localized dir
	String userLocale = Locale.getDefault().getLanguage();
	File locDir = new File(rootpath, userLocale);
	    Log.d(TAG, "loc dir=" + locDir.getAbsolutePath());
	if ( locDir.exists() == false ) {
	    // need to find a fallback... ouch
	    Log.d(TAG, "loc dir=" + locDir.getAbsolutePath() + " not found");
	    locDir = new File(rootpath, "ja");
	}

	try {
	    readContent( new File(locDir, CONTENT_FILE_NAME) );
	}
	catch(IOException e) {

	}
	catch(Exception e){
	    e.printStackTrace();
	}
    }

    private void readContent(File contentFile) throws IOException {
	if ( contentFile.exists() ) {
	    evtTable = new Hashtable<String, String>();

	    InputStream is = new FileInputStream(contentFile);
	    BufferedReader br = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8") ) );
	    String line = null;
	    while( true ) {
		line = br.readLine();
		if ( line == null )
			break;
		if ( line.startsWith(";") )
		    continue;
		if ( line.indexOf(',') == -1 )
		    continue; // bad line

		int idx = line.indexOf(',');
		String key = line.substring(0,idx);
		String val = line.substring(idx + 1);
		evtTable.put(key, val);
	    }
	}
    }

    public void terminate() {

    }

    public String getModuleName() {
	return "NanidroidShiori";
    }
    
    
    @Override
    protected String genResponse() {
	if ( reqTable == null  || mCtx == null )
	    return super.genResponse();
	
	String reqId = reqTable.get("id");
	if ( reqId.equalsIgnoreCase("OnGhostChanging")) {
	    return  handleGhostChanging();//RES_HEADER + reqTable.get("id") + "\\e";
	}
	else if ( reqId.equalsIgnoreCase("OnGhostChanged")) {
	    return handleGhostChanged();
	}
	else if ( reqId.equalsIgnoreCase("OnClose") ) {
	    return handleOnClose();
	}
	else if ( reqId.equalsIgnoreCase("OnBoot")) {
	    return handleOnBoot();
	}
	else if ( reqId.equalsIgnoreCase("OnInstallFailure") ) {
	    return handleInstallFail();
	}
		

	if ( evtTable.get(reqId) != null )
	    return RES_HEADER + evtTable.get(reqId) + RES_END;

	return RES_NO_CONTENT;
	//	return super.genResponse();
    }

    private String handleGhostChanging() {
	if ( evtTable.get("OnGhostChanging") != null ) {
	    String s = evtTable.get("OnGhostChanging");    
	    return RES_HEADER + String.format(s, reqTable.get("reference0")) + RES_END;
	}
	
	return RES_NO_CONTENT;
    }

    private String handleGhostChanged() {
	if ( evtTable.get("OnGhostChanged") != null ) {
	    String s = evtTable.get("OnGhostChanged");
	    return RES_HEADER + String.format(s, reqTable.get("reference0")) + RES_END;
	}

	return RES_NO_CONTENT;// RES_HEADER + reqTable.get("reference0") + RES_END;
    }

    private String handleOnClose() {
	if ( evtTable.get("OnClose") != null ) {
	    String s = evtTable.get("OnClose");
	    return RES_HEADER + s + RES_END;
	}

	return RES_HEADER + "OnClose" + RES_END;
    }

    private String handleInstallFail() {
	if ( evtTable.get("OnInstallFailure") != null ) {

	    return RES_HEADER + evtTable.get("OnInstallFailure") + RES_END;
	}
	
	return RES_NO_CONTENT;
    }

    private String handleOnBoot() {
	if ( evtTable.get("OnBoot") != null ) {
	    return RES_HEADER + evtTable.get("OnBoot") + RES_END;
	}
	return RES_NO_CONTENT;
    }

}
