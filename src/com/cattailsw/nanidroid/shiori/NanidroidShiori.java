package com.cattailsw.nanidroid.shiori;

import java.util.HashSet;
import java.util.Hashtable;

import android.content.Context;

public class NanidroidShiori extends EchoShiori {
    private static final String TAG = "NanidroidShiori";
    HashSet<String> igTable = new HashSet<String>();
    public static final String RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT";
    private static final String RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: ";
    private static final String RES_END = "\\e\\r\\n\\r\\n";
    String header = null;
    private Context mCtx = null;

    //    Hashtable<String, String> reqTable = null;
    
    public NanidroidShiori() {

    }

    public NanidroidShiori(Context ctx) {
	mCtx = ctx;
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
	    return RES_NO_CONTENT;
	}
	else if ( reqId.equalsIgnoreCase("OnInstallFailure") ) {
	    return handleInstallFail();
	}
		
	return RES_NO_CONTENT;
	//	return super.genResponse();
    }

    private String handleGhostChanging() {
	return RES_HEADER + reqTable.get("reference0") + RES_END;
    }

    private String handleGhostChanged() {
	return RES_HEADER + reqTable.get("reference0") + RES_END;
    }

    private String handleOnClose() {
	return RES_HEADER + "OnClose" + RES_END;
    }

    private String handleInstallFail() {
	return RES_HEADER + "Install Failed" + RES_END;
    }
}
