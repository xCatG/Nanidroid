package com.cattailsw.nanidroid.shiori;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Hashtable;
import android.util.Log;
import java.util.HashSet;

public class EchoShiori implements Shiori {
    private static final String TAG = "EchoShiori";

    HashSet<String> igTable = new HashSet<String>();

    public EchoShiori() {
	for( String s : ignoreId )
	    igTable.add(s);
    }

    public String getModuleName() {
	return "EchoShiori";
    }

    private static String[] ignoreId = {"OnSecondChange"};

    public String request(String req) {
	// need to parse the header and return request in sakura script
	parseRequest(req);
	return genResponse();
    }
    
    protected String genResponse() {
    	if ( reqTable != null ) {
    	    if ( matchIgnoreId( reqTable.get("id") ) == false ) {
    		return "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: " + reqTable.get("id") + "\\e";
    	    }
    	    else
    		return "SHIORI/3.0 204 NO CONTENT";
    	}

    	return "SHIORI/3.0 400 BAD REQUEST";    	
    }

    public void terminate() {
	// do nothing
    }

    private boolean matchIgnoreId(String in) {
	return (igTable.contains(in));
    }

    String header = null;
    Hashtable<String, String> reqTable = null;

    private void parseRequest(String req) {
	// first line should be GET SHIORI/3.0 or something like that
	BufferedReader br = new BufferedReader( new StringReader( req ) );
	try {
	    header = br.readLine();
	}
	catch(Exception e){
	    return;
	}
	String line = null;
	reqTable = new Hashtable<String, String>();
	while( true ) {
	    try {
		line = br.readLine();
	    }
	    catch(Exception e) {
		line = null;
	    }
	    if ( line == null ) break;
	    if ( line.indexOf(": ") == -1 ) { Log.d(TAG, "got a non recognized line\n"+ line); continue;}

	    String key = line.substring(0, line.indexOf(": ")).toLowerCase();
	    String val = line.substring(line.indexOf(": ") + 2);
	    reqTable.put(key, val);
	}
    }

}
