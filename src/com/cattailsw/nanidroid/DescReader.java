package com.cattailsw.nanidroid;

import java.util.Map;
import java.io.File;
import java.io.InputStream;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.io.InputStreamReader;
import android.util.Log;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.io.FileInputStream;
import java.util.Hashtable;
import java.io.IOException;

public class DescReader {
    private static final String TAG = "DescReader";
    Map<String, String> table;

    public DescReader() {

    }

    public DescReader(File f) {
	try {
	    InputStream is = new FileInputStream(f);
	    parse(is);
	}
	catch(FileNotFoundException e) {}
	catch(IOException e) {}
    }

    public DescReader(InputStream is) {
	try {
	    parse(is);
	}
	catch(Exception e) {

	}
    }

    private void parse(InputStream is) throws IOException{
	if ( table == null )
	    table = new Hashtable<String, String>();

	BufferedReader reader = null;
	try {
	    reader = new BufferedReader(new InputStreamReader(is, Charset.forName("SJIS")));
	}
	catch(Exception e) {
	    Log.d(TAG, "error reading");
	    return;
	}

	String line = null;
	while ( true ) {
	    line = reader.readLine();
	    if ( line == null ) 
		break;
	    if ( line.indexOf(',') == -1 ) 
		continue; // ignore lines started with ,

	    // should split line into pairs
	    String[] pair = line.split(",");
	    if ( pair == null || pair.length != 2 ) 
		continue; // error line
	    String label = pair[0];
	    String value = pair[1];

	    table.put(label, value);
	}

	reader.close();
    }

}