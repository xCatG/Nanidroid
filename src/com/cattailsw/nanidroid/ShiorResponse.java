package com.cattailsw.nanidroid;

import java.util.Hashtable;
import java.io.BufferedReader;

public class ShiorResponse {
    String header;
    Hashtable<String, String> resp;

    public ShiorResponse(String h) {
	this(h, new Hashtable<String, String>(0));
    }

    public ShiorResponse(String h, Hashtable<String, String> res) {
	header = h;
	resp = res;
    }

    public ShiorResponse(BufferedReader br){
	try {
	    header = br.readLine();
	}
	catch(Exception e) {
	    
	}

	resp = new Hashtable<String, String>();
	String line = null;
	while( true ) {
	    try {
		line = br.readLine();
	    }
	    catch(Exception e) {
		line = null;
	    }
	    if ( line == null )
		break;

	    if ( line.indexOf(":") == -1 ) // not a "xxx: xxx" format line, ignore
		continue;

	    String key = line.substring(0, line.indexOf(":"));
	    String val = line.substring(line.indexOf(":") + 2); // from end of ": " to end of line
	    resp.put(key, val);
	}
    }

    public String getHeader() {
	return header;
    }

    public Hashtable<String, String> getResponse() {
	return resp;
    }

    public String getKey(String key){
	return resp.get(key);
    }

}
