package com.cattailsw.nanidroid.shiori;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Hashtable;

import android.util.Log;

/**
 * Describe class NanidroidShiori here.
 *
 *
 * Created: Mon May 07 10:53:47 2012
 *
 * @author <a href="mailto:"></a>
 * @version 1.0
 */
public class NanidroidShiori extends EchoShiori {
    private static final String TAG = "NanidroidShiori";
    HashSet<String> igTable = new HashSet<String>();
    public static final String RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT";
    private static final String RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: ";
    String header = null;

    Hashtable<String, String> reqTable = null;
    
    public NanidroidShiori() {

    }
    public void terminate() {

    }

    public String getModuleName() {
	return "NanidroidShiori";
    }
    
    
	@Override
	protected String genResponse() {
		if ( reqTable == null )
			return super.genResponse();
		
		String reqId = reqTable.get("id");
		if ( reqId.equalsIgnoreCase("OnGhostChanging")) {
			return  RES_HEADER + reqTable.get("id") + "\\e";
		}
		else if ( reqId.equalsIgnoreCase("OnBoot")) {
			return RES_NO_CONTENT;
		}
		
		return super.genResponse();
	}

    
}
