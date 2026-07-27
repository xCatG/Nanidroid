package com.cattailsw.nanidroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Hashtable;
import java.util.Map;

import android.os.SystemClock;
import android.util.Log;

import com.cattailsw.nanidroid.util.NarUtil;
import com.cattailsw.nanidroid.util.AnalyticsUtils;

public class DescReader {
    private static final Charset DEF_CHARSET = Charset.forName("Shift_JIS");
	private static final String TAG = "DescReader";
    private Map<String, String> table;

    String infilePath = null;

    public DescReader() {}

    public DescReader(String infile) { infilePath = infile; }

    public DescReader(File f) {
	try { InputStream is = new FileInputStream(f); parse(is); }
	catch(FileNotFoundException e) {}
	catch(IOException e) {e.printStackTrace();}
    }

    public DescReader(InputStream is) {
	try { dbgOutput = true; parse(is); }
	catch(Exception e) { Log.d(TAG, "parsing inputstream error"); e.printStackTrace(); }
    }
	
    boolean dbgOutput = false;
	
    public void setDbgOutput(boolean dbg) { dbgOutput = dbg; }

    private Charset readFirstLineForCharset(BufferedReader br) throws IOException {
	Charset c =DEF_CHARSET; 
	if ( br.markSupported() == false ) return c;
	String line = br.readLine();
	if ( line.startsWith( NarUtil.UTF8_BOM ) ) line = line.substring(1);
	String [] cs = line.split(",");
	if ( cs == null || cs.length != 2 ) return c;
	if ( cs[0].contains("charset") == false ) return c;
	try { c = Charset.forName(cs[1]); }
	catch(Exception e) { Log.d(TAG, "trouble charset is:" + cs[1]); }
	return c;
    }

    private void parse(InputStream is) throws IOException{
	if ( getTable() == null ) setTable(new Hashtable<String, String>());
	BufferedReader reader = new BufferedReader(new InputStreamReader(is, DEF_CHARSET));
	Charset c = readFirstLineForCharset(reader);
	reader.close();
	reader = new BufferedReader(new InputStreamReader(is, c ) );
	readLoop(reader, getTable());
	reader.close();
    }

    private void readLoop(BufferedReader reader, Map<String, String> table) throws IOException{
	String line = null;
	while ( true ) {
	    line = reader.readLine();
	    if ( line == null ) break;
	    if ( line.indexOf(',') == -1 ) continue;
	    String[] pair = line.split(",");
	    if ( pair == null || pair.length != 2 ) continue;
	    String label = pair[0];
	    String value = pair[1];
	    if ( dbgOutput ) Log.d(TAG, "putting [" + label + "," + value + "]");
	    table.put(label, value);
	}
    }

    long parseTime;

    public Map<String,String> parse() throws IOException {
	parseTime = SystemClock.uptimeMillis();
	Hashtable<String, String> ret = new Hashtable<String,String>();
	BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(infilePath)), DEF_CHARSET));
	Charset c = readFirstLineForCharset(reader);
	reader.close();
	reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(infilePath)), c));
	readLoop(reader, ret);
	reader.close();
	parseTime = SystemClock.uptimeMillis() - parseTime;
	Log.d(TAG, "parsing took:" + parseTime + "ms");
	AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PERF, "parsing time[ms]", infilePath, (int)parseTime);
	return ret;
    }

    public Map<String, String> getTable() { return table; }
    public void setTable(Map<String, String> table) { this.table = table; }
}
