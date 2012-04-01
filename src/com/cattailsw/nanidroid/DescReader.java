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
import java.io.FileReader;
import android.os.SystemClock;

public class DescReader {
    private static final String TAG = "DescReader";
    Map<String, String> table;

    String infilePath = null;

    public DescReader() {

    }

    public DescReader(String infile) {
	//this(new File(infile));
	infilePath = infile;
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
	    reader = new BufferedReader(new InputStreamReader(is, Charset.forName("Shift_JIS")));
	}
	catch(Exception e) {
	    Log.d(TAG, "error reading");
	    return;
	}

	readLoop(reader, table);

	reader.close();
    }

    private void readLoop(BufferedReader reader, Map<String, String> table) throws IOException{
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
	    Log.d(TAG, "putting [" + label + "," + value + "]");
	    table.put(label, value);
	}
    }

    long parseTime;

    public Map<String,String> parse() throws IOException {
	parseTime = SystemClock.uptimeMillis();
	Hashtable<String, String> ret = new Hashtable<String,String>();
	
	BufferedReader reader = null;
	try {
	    reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(infilePath)), 
							      Charset.forName("Shift_JIS")));
	}
	catch(Exception e) {
	    Log.d(TAG, "error reading:" + infilePath);
	    return null;
	}
	readLoop(reader, ret);

	reader.close();
	parseTime = SystemClock.uptimeMillis() - parseTime;
	Log.d(TAG, "parsing took:" + parseTime + "ms");
	return ret;
    }
    

}
