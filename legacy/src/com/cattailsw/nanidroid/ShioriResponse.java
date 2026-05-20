package com.cattailsw.nanidroid;

import java.util.Hashtable;
import java.io.BufferedReader;
import org.apache.http.ProtocolVersion;
import java.util.regex.Matcher;

public class ShioriResponse {
    String header;
    Hashtable<String, String> resp;

    ProtocolVersion _ver;
    int stat_code = 500;

    public ProtocolVersion getProtocolVersion() {
	return _ver;
    }

    public ShioriResponse(String h) {
	this(h, new Hashtable<String, String>(0));
    }

    public ShioriResponse(String h, Hashtable<String, String> res) {
	header = h;
	resp = res;
	parseHeader();
    }

    public ShioriResponse(BufferedReader br){
	try {
	    header = br.readLine();
	}
	catch(Exception e) {
	    
	}
	parseHeader();

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

    private void parseHeader() {
	// need to parse and create prototol version
	//_ver = new ProtocolVersion("SHIORI", 3, 0);
	if ( header == null )
	    return;

	Matcher m = PatternHolders.shiori_res_header_ptrn.matcher(header);
	if ( m.matches() ) {
	    try {
		int mj = Integer.parseInt(m.group(2));
		int mi = Integer.parseInt(m.group(3));
		stat_code = Integer.parseInt(m.group(4));

		_ver = new ProtocolVersion(m.group(1), mj, mi);

	    }
	    catch(Exception e) {

	    }
	}
    }

    public String getHeader() {
	return header;
    }

    public int getStatusCode() {
	return stat_code;
    }

    public Hashtable<String, String> getResponse() {
	return resp;
    }

    public String getKey(String key){
	return resp.get(key);
    }

    @Override
    public String toString(){
	StringBuilder sb = new StringBuilder();
	sb.append("Response:" + _ver + " " + stat_code + "\n");
	if ( resp != null ) {
	    for( String k : resp.keySet()) {
		sb.append(k + ": " + resp.get(k) + "\n");
	    }
	}
	return  sb.toString();
    }

}
